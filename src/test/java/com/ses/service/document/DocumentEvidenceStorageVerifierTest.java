package com.ses.service.document;

import com.ses.service.document.DocumentEvidenceEligibility.EligibleVersion;
import com.ses.service.document.DocumentEvidenceStorageVerifier.Outcome;
import com.ses.service.storage.DocumentStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * U9（NF01細粒度epoch計画 §5.1 条件10・§5.4）: {@link DocumentEvidenceStorageVerifier} がstorageの内容を
 * 版のSHA-256・大きさと照合し、lockを持つtransactionの中では呼べないことを確かめる。
 */
class DocumentEvidenceStorageVerifierTest {

    private static final byte[] CONTENT = "skill-sheet-pdf".getBytes(StandardCharsets.UTF_8);

    private DocumentStorage storage;
    private DocumentEvidenceStorageVerifier verifier;

    @BeforeEach
    void setUp() {
        storage = mock(DocumentStorage.class);
        verifier = new DocumentEvidenceStorageVerifier(storage);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 内容のSHA256と大きさが版と一致すればMATCH() throws Exception {
        when(storage.open("k/1")).thenReturn(new ByteArrayInputStream(CONTENT));

        assertEquals(Outcome.MATCH, verifier.verify(version(sha256(CONTENT).toUpperCase(Locale.ROOT), (long) CONTENT.length)));
    }

    @Test
    void 内容や大きさが違えばMISMATCH() throws Exception {
        when(storage.open("k/1")).thenAnswer(invocation -> new ByteArrayInputStream("tampered".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Outcome.MISMATCH, verifier.verify(version(sha256(CONTENT), null)));
        assertEquals(Outcome.MISMATCH, verifier.verify(version(sha256(CONTENT), (long) CONTENT.length)));

        when(storage.open("k/1")).thenAnswer(invocation -> new ByteArrayInputStream(CONTENT));
        assertEquals(Outcome.MISMATCH, verifier.verify(version(sha256(CONTENT), CONTENT.length + 1L)), "大きさの不一致");
        assertEquals(Outcome.MISMATCH, verifier.verify(version("zz", (long) CONTENT.length)), "hash形式の不正");
    }

    @Test
    void 上限を超える内容は読み切らずにMISMATCH() throws Exception {
        DocumentEvidenceStorageVerifier small = new DocumentEvidenceStorageVerifier(storage, 4);
        when(storage.open("k/1")).thenReturn(new ByteArrayInputStream(CONTENT));

        assertEquals(Outcome.MISMATCH, small.verify(version(sha256(CONTENT), null)));
    }

    @Test
    void storageを開けなければUNREADABLE() {
        when(storage.open("k/1")).thenThrow(new IllegalStateException("missing"));
        assertEquals(Outcome.UNREADABLE, verifier.verify(version("ab".repeat(32), 1L)));

        DocumentStorage nullStorage = mock(DocumentStorage.class);
        assertEquals(Outcome.UNREADABLE, new DocumentEvidenceStorageVerifier(nullStorage).verify(version("ab".repeat(32), 1L)));
    }

    @Test
    void DB_transactionの中ではstorageを開かず拒否する() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class, () -> verifier.verify(version("ab".repeat(32), 1L)));
        verifyNoInteractions(storage);
    }

    private static EligibleVersion version(String sha256, Long size) {
        return new EligibleVersion("tenant-a", 11L, 12L, 1, "k/1", sha256, size);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
