package com.ses.importcenter.metadata;

import com.ses.service.DocumentService;
import com.ses.service.impl.StagedImportDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 照合の判定規則を、専用のH2 in-memory DB（本class固有の名前、共有H2は使わない）で実SQLを通して確認する。
 * 実MySQLでの同じ判定はImportCsvIngestionRealMySqlTestが確認する。
 */
class ImportStageReconcilerTest {
    private static final Instant RECORDED = Instant.parse("2026-11-13T00:00:00Z");

    @TempDir Path root;

    private JdbcTemplate jdbc;
    private DocumentService documents;
    private SimpleMeterRegistry meters;
    private ImportStageReconciliationLedger ledger;

    @BeforeEach
    void prepareIsolatedDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:nf06_stage_reconcile_" + UUID.randomUUID().toString().replace("-", "")
                        + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE t_document_version (id BIGINT PRIMARY KEY, tenant_id VARCHAR(100), "
                + "storage_key VARCHAR(255), deleted_flag INT)");
        documents = mock(DocumentService.class);
        meters = new SimpleMeterRegistry();
        ledger = new ImportStageReconciliationLedger(root.resolve(ImportStageReconciliationLedger.DIRECTORY), meters,
                Clock.fixed(RECORDED, ZoneOffset.UTC));
    }

    @AfterEach
    void dropIsolatedDatabase() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void entryInsideSafetyWindowIsNotJudged() {
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(key(), UUID.randomUUID().toString()));

        ImportStageReconciler.Result result = reconcilerAt(RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW)
                .minusSeconds(1)).reconcileDue();

        assertEquals(1, result.pending());
        assertEquals(1, ledger.entries().size());
        verify(documents, never()).discardImportStage(any());
    }

    @Test
    void unreferencedStageIsReclaimedAndRemovedFromTheLedger() {
        String storageKey = key();
        String operationId = UUID.randomUUID().toString();
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(storageKey, operationId));

        ImportStageReconciler.Result result = reconcilerAt(RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW))
                .reconcileDue();

        assertEquals(1, result.reclaimed());
        ArgumentCaptor<StagedImportDocument> discarded = ArgumentCaptor.forClass(StagedImportDocument.class);
        verify(documents).discardImportStage(discarded.capture());
        assertEquals(storageKey, discarded.getValue().storageKey());
        assertEquals(storageKey, discarded.getValue().version().getStorageKey());
        assertEquals("IMPORT", discarded.getValue().document().getDocumentType());
        assertEquals(operationId, discarded.getValue().operationId());
        assertEquals(List.of(), ledger.entries());
        assertEquals(1, meters.find(ImportStageReconciler.RECONCILED_METRIC).tag("result", "reclaimed").counter().count());
    }

    @Test
    void stageReferencedByAnyVersionIsKeptEvenIfThatVersionIsLogicallyDeletedOrInAnotherTenant() {
        String storageKey = key();
        jdbc.update("INSERT INTO t_document_version (id, tenant_id, storage_key, deleted_flag) VALUES (1, ?, ?, 1)",
                "tenant-other", storageKey);
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(storageKey, UUID.randomUUID().toString()));

        ImportStageReconciler.Result result = reconcilerAt(RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW))
                .reconcileDue();

        assertEquals(1, result.committed());
        verify(documents, never()).discardImportStage(any());
        assertEquals(List.of(), ledger.entries());
    }

    @Test
    void failedDeletionKeepsTheEntryForTheNextRun() {
        ledger.recordDiscardFailure("tenant-a", ImportStageReconciliationLedgerTest.staged(key(),
                UUID.randomUUID().toString()), "winner");
        doThrow(new IllegalStateException("synthetic storage outage")).when(documents).discardImportStage(any());

        ImportStageReconciler.Result result = reconcilerAt(RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW))
                .reconcileDue();

        assertEquals(1, result.failed());
        assertEquals(1, ledger.entries().size(), "削除できなかった台帳は残して再試行する");
    }

    @Test
    void storageKeyOutsideTheGeneratedFormatIsNeverDeletedAutomatically() {
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged("../../uploads/other",
                UUID.randomUUID().toString()));

        ImportStageReconciler.Result result = reconcilerAt(RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW))
                .reconcileDue();

        assertEquals(1, result.manual());
        assertTrue(result.touched());
        verify(documents, never()).discardImportStage(any());
        assertEquals(1, ledger.entries().size());
    }

    private ImportStageReconciler reconcilerAt(Instant now) {
        return new ImportStageReconciler(ledger, jdbc, documents, meters, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String key() {
        return UUID.randomUUID().toString().replace("-", "") + "/" + UUID.randomUUID();
    }
}
