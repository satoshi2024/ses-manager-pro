package com.ses.importcenter.metadata;

import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.service.impl.StagedImportDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 照合台帳の記録・列挙・削除と、記録失敗を例外にせず通知することを確認する（DB・Spring不要）。 */
class ImportStageReconciliationLedgerTest {
    private static final String STORAGE_KEY = "0123456789abcdef0123456789abcdef/" + UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");

    @TempDir Path root;

    @Test
    void retainedStageIsRecordedWithoutCsvContentAndListedInRecordedOrder() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ImportStageReconciliationLedger ledger = ledger(meters, Clock.fixed(NOW, ZoneOffset.UTC));
        String operationId = UUID.randomUUID().toString();

        assertTrue(ledger.recordRetained("tenant-a", staged(STORAGE_KEY, operationId)));

        List<ImportStageReconciliationLedger.Entry> entries = ledger.entries();
        assertEquals(1, entries.size());
        ImportStageReconciliationLedger.Entry entry = entries.get(0);
        assertEquals("tenant-a", entry.tenantId());
        assertEquals(STORAGE_KEY, entry.storageKey());
        assertEquals(operationId, entry.operationId());
        assertEquals(ImportStageReconciliationLedger.Reason.OUTCOME_UNKNOWN, entry.reason());
        assertEquals(NOW, entry.recordedAt());
        assertEquals(operationId + ImportStageReconciliationLedger.SUFFIX, entry.file().getFileName().toString());
        String content = Files.readString(entry.file(), StandardCharsets.ISO_8859_1);
        assertFalse(content.contains("架空会社"), "台帳へCSV本文を書かない");
        assertEquals(1, meters.find(ImportStageReconciliationLedger.RETAINED_METRIC).counter().count());
    }

    @Test
    void discardFailureIsRecordedWithPathTagAndNonUuidOperationIdDoesNotBecomeAFileName() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ImportStageReconciliationLedger ledger = ledger(meters, Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(ledger.recordDiscardFailure("tenant-a", staged(STORAGE_KEY, "../escape"), "winner"));

        ImportStageReconciliationLedger.Entry entry = ledger.entries().get(0);
        assertEquals(ImportStageReconciliationLedger.Reason.DISCARD_FAILED, entry.reason());
        assertEquals("../escape", entry.operationId());
        assertEquals(root.resolve(ImportStageReconciliationLedger.DIRECTORY).toAbsolutePath().normalize(),
                entry.file().getParent(), "operation IDをfile名に使わずdirectory外へ書かない");
        assertEquals(1, meters.find(ImportStageReconciliationLedger.DISCARD_FAILURE_METRIC).tag("path", "winner")
                .counter().count());
    }

    @Test
    void removeDeletesOnlyEntriesInsideTheLedgerDirectory() throws Exception {
        ImportStageReconciliationLedger ledger = ledger(null, Clock.systemUTC());
        ledger.recordRetained("tenant-a", staged(STORAGE_KEY, UUID.randomUUID().toString()));
        ImportStageReconciliationLedger.Entry entry = ledger.entries().get(0);

        Path outside = Files.writeString(root.resolve("outside.properties"), "x");
        ImportStageReconciliationLedger.Entry forged = new ImportStageReconciliationLedger.Entry(outside,
                "tenant-a", STORAGE_KEY, "op", ImportStageReconciliationLedger.Reason.OUTCOME_UNKNOWN, NOW);
        assertThrows(java.io.IOException.class, () -> ledger.remove(forged));
        assertTrue(Files.exists(outside));

        ledger.remove(entry);
        assertEquals(List.of(), ledger.entries());
    }

    @Test
    void unreadableEntryIsSkippedButNotDeleted() throws Exception {
        ImportStageReconciliationLedger ledger = ledger(null, Clock.systemUTC());
        Files.createDirectories(ledger.directory());
        Path broken = Files.writeString(ledger.directory().resolve("broken" + ImportStageReconciliationLedger.SUFFIX),
                "reason=NOT_A_REASON\n");

        assertEquals(List.of(), ledger.entries());
        assertTrue(Files.exists(broken), "読めない台帳は手作業照合のため残す");
    }

    @Test
    void writeFailureIsReportedWithoutThrowing() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Path notADirectory = Files.writeString(root.resolve("blocked"), "file");
        ImportStageReconciliationLedger ledger = new ImportStageReconciliationLedger(notADirectory, meters,
                Clock.systemUTC());

        assertFalse(ledger.recordRetained("tenant-a", staged(STORAGE_KEY, UUID.randomUUID().toString())));
        assertFalse(ledger.recordRetained("tenant-a", null));

        assertEquals(2, meters.find(ImportStageReconciliationLedger.LEDGER_FAILURE_METRIC).counter().count());
    }

    private ImportStageReconciliationLedger ledger(SimpleMeterRegistry meters, Clock clock) {
        return new ImportStageReconciliationLedger(root.resolve(ImportStageReconciliationLedger.DIRECTORY), meters,
                clock);
    }

    static StagedImportDocument staged(String storageKey, String operationId) {
        Document document = new Document();
        document.setDocumentType("IMPORT");
        DocumentVersion version = new DocumentVersion();
        version.setStorageKey(storageKey);
        return new StagedImportDocument(document, version, storageKey, operationId);
    }
}
