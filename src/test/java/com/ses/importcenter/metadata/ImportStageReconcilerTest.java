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
import java.time.Duration;
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

    /**
     * b-14（W2-NF06 review P3-1）: 1時間を過ぎても、COMMITを送った接続のserver threadが文を実行中なら判定せず残す。
     * 実行していないと確かめた後に回収する。参照があればthreadを確かめずにcommit済みとする。
     */
    @Test
    void COMMIT接続が実行中の間は参照が無くても回収せず終了後に回収する() {
        String storageKey = key();
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(storageKey,
                UUID.randomUUID().toString()), 4242L);
        java.util.concurrent.atomic.AtomicBoolean executing = new java.util.concurrent.atomic.AtomicBoolean(true);
        List<Long> probed = new java.util.ArrayList<>();
        ImportStageReconciler reconciler = reconcilerAt(RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW).plusSeconds(3600),
                id -> { probed.add(id); return executing.get(); });

        ImportStageReconciler.Result waiting = reconciler.reconcileDue();

        assertEquals(1, waiting.pending());
        assertEquals(0, waiting.reclaimed());
        verify(documents, never()).discardImportStage(any());
        assertEquals(1, ledger.entries().size(), "COMMIT接続が実行中の台帳は残す");
        assertEquals(List.of(4242L), probed);

        executing.set(false);
        ImportStageReconciler.Result done = reconciler.reconcileDue();

        assertEquals(1, done.reclaimed());
        verify(documents).discardImportStage(any());
        assertEquals(List.of(), ledger.entries());
    }

    @Test
    void COMMIT接続の確認中に確定したcommitは数え直しでcommit済みとし確認できなければ台帳を残す() {
        String committedKey = key();
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(committedKey,
                UUID.randomUUID().toString()), 7L);
        // 最初の参照件数の後、接続の確認の間にCOMMITが確定して版が見えるようになった状態を再現する。
        ImportStageReconciler.Result raced = reconcilerAt(RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW), id -> {
            jdbc.update("INSERT INTO t_document_version (id, tenant_id, storage_key, deleted_flag) VALUES (9, ?, ?, 0)",
                    "tenant-a", committedKey);
            return false;
        }).reconcileDue();

        assertEquals(1, raced.committed());
        verify(documents, never()).discardImportStage(any());
        assertEquals(List.of(), ledger.entries());

        String unknownKey = key();
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(unknownKey,
                UUID.randomUUID().toString()), 8L);
        ImportStageReconciler.Result unavailable = reconcilerAt(RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW), id -> {
            throw new IllegalStateException("synthetic processlist outage");
        }).reconcileDue();

        assertEquals(1, unavailable.failed());
        verify(documents, never()).discardImportStage(any());
        assertEquals(1, ledger.entries().size(), "接続の状態を確かめられなければ消さない");
    }

    @Test
    void 参照がある台帳と接続IDの無い台帳はCOMMIT接続を確かめない() {
        String storageKey = key();
        jdbc.update("INSERT INTO t_document_version (id, tenant_id, storage_key, deleted_flag) VALUES (3, ?, ?, 0)",
                "tenant-a", storageKey);
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(storageKey,
                UUID.randomUUID().toString()), 11L);
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(key(), UUID.randomUUID().toString()));
        List<Long> probed = new java.util.ArrayList<>();

        ImportStageReconciler.Result result = reconcilerAt(RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW),
                id -> { probed.add(id); return true; }).reconcileDue();

        assertEquals(1, result.committed());
        assertEquals(1, result.reclaimed(), "接続IDの無い台帳は従来どおり時間だけで判定する");
        assertEquals(List.of(), probed);
    }

    private ImportStageReconciler reconcilerAt(Instant now) {
        return new ImportStageReconciler(ledger, jdbc, documents, meters, Clock.fixed(now, ZoneOffset.UTC));
    }

    /**
     * RB-NF05 review P3-3: 接続IDはserverの再起動・failoverの後に別の接続へ振り直される。記録後にserverが再起動した
     * （起動時刻が記録時刻＋許容より後）・別serverになった（server_uuidが違う）なら、IDが実行中に見えても使わず回収する。
     */
    @Test
    void 記録後にDB_serverが再起動または切り替わった台帳は接続IDを使わず時間で判定する() {
        String restartedKey = key();
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(restartedKey,
                UUID.randomUUID().toString()), 21L, SERVER_UUID);
        List<Long> probed = new java.util.ArrayList<>();
        Instant now = RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW);

        ImportStageReconciler.Result restarted = reconcilerAt(now, id -> { probed.add(id); return true; },
                new ImportStageReconciler.ServerIdentity(SERVER_UUID,
                        RECORDED.plus(ImportStageReconciler.SERVER_RESTART_MARGIN).plusSeconds(1))).reconcileDue();

        assertEquals(1, restarted.reclaimed(), "再起動したserverでは同じIDが別の接続のものになり得る");
        assertEquals(List.of(), probed);

        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(key(),
                UUID.randomUUID().toString()), 22L, SERVER_UUID);
        ImportStageReconciler.Result failedOver = reconcilerAt(now, id -> { probed.add(id); return true; },
                new ImportStageReconciler.ServerIdentity("aaaaaaaa-71ca-11e1-9e33-c80aa9429562",
                        RECORDED.minus(Duration.ofDays(30)))).reconcileDue();

        assertEquals(1, failedOver.reclaimed(), "server_uuidが違えば別server（failover）");
        assertEquals(List.of(), probed);
        verify(documents, org.mockito.Mockito.times(2)).discardImportStage(any());
    }

    @Test
    void 時計の誤差の範囲の起動時刻と同じserverでは接続IDを使いserverを取れなければ台帳を残す() {
        ledger.recordRetained("tenant-a", ImportStageReconciliationLedgerTest.staged(key(),
                UUID.randomUUID().toString()), 31L, SERVER_UUID.toUpperCase(java.util.Locale.ROOT));
        List<Long> probed = new java.util.ArrayList<>();
        Instant now = RECORDED.plus(ImportStageReconciler.SAFETY_WINDOW);

        ImportStageReconciler.Result withinMargin = reconcilerAt(now, id -> { probed.add(id); return true; },
                new ImportStageReconciler.ServerIdentity(SERVER_UUID,
                        RECORDED.plus(ImportStageReconciler.SERVER_RESTART_MARGIN))).reconcileDue();

        assertEquals(1, withinMargin.pending(), "許容内の起動時刻は再起動とみなさず、実行中の接続を待つ");
        assertEquals(List.of(31L), probed);

        ImportStageReconciler.Result unknownServer = new ImportStageReconciler(ledger, jdbc, documents, meters,
                Clock.fixed(now, ZoneOffset.UTC), new ImportStageReconciler.CommitConnectionProbe() {
                    @Override public boolean isExecuting(long connectionId) { return false; }
                    @Override public ImportStageReconciler.ServerIdentity currentServer() {
                        throw new IllegalStateException("synthetic status outage");
                    }
                }).reconcileDue();

        assertEquals(1, unknownServer.failed());
        assertEquals(1, ledger.entries().size(), "serverを確かめられなければ消さない");
        verify(documents, never()).discardImportStage(any());
    }

    /** 試験の既定: 記録より前から動いている同じserver。 */
    private static final String SERVER_UUID = "3e11fa47-71ca-11e1-9e33-c80aa9429562";
    private static final ImportStageReconciler.ServerIdentity SAME_SERVER =
            new ImportStageReconciler.ServerIdentity(SERVER_UUID, RECORDED.minus(Duration.ofDays(1)));

    private ImportStageReconciler reconcilerAt(Instant now, java.util.function.LongPredicate executing) {
        return reconcilerAt(now, executing, SAME_SERVER);
    }

    private ImportStageReconciler reconcilerAt(Instant now, java.util.function.LongPredicate executing,
                                               ImportStageReconciler.ServerIdentity server) {
        return new ImportStageReconciler(ledger, jdbc, documents, meters, Clock.fixed(now, ZoneOffset.UTC),
                new ImportStageReconciler.CommitConnectionProbe() {
                    @Override public boolean isExecuting(long connectionId) { return executing.test(connectionId); }
                    @Override public ImportStageReconciler.ServerIdentity currentServer() { return server; }
                });
    }

    private static String key() {
        return UUID.randomUUID().toString().replace("-", "") + "/" + UUID.randomUUID();
    }
}
