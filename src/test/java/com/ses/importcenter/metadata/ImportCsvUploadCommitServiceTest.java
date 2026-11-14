package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.impl.DocumentMetadataCommitService;
import com.ses.service.impl.StagedImportDocument;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * metadata commitが総期限の残り秒数だけをtransaction timeoutにし、失敗を
 * 「未commit確定（元例外のまま）」と「COMMIT結果不明（ImportCommitOutcomeUnknownException）」に分けることを確認する。
 */
class ImportCsvUploadCommitServiceTest {
    private static final String SHA = "a".repeat(64);
    private static final ImportAuthorizedContext CONTEXT = new ImportAuthorizedContext("tenant-test", 31, 41);
    private static final ImportAdmissionToken TOKEN = new ImportAdmissionToken("tenant-test", 31, 41, 51, 3,
            ImportEntity.CUSTOMER, SesImportV1Schema.VERSION, ImportEncoding.UTF8, "unit-fixed-fixture");
    private static final ImportAdmissionManifest MANIFEST = new ImportAdmissionManifest("unit-fixed-fixture",
            "tenant-test", 41, 51, 3, ImportEntity.CUSTOMER, SesImportV1Schema.VERSION, ImportEncoding.UTF8, SHA,
            null, null, null, null, "A1A_CONTENT");

    private final ImportMetadataAuthorizationService authorization = mock(ImportMetadataAuthorizationService.class);
    private final DocumentMetadataCommitService documentCommit = mock(DocumentMetadataCommitService.class);
    private final ImportMetadataService metadata = mock(ImportMetadataService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ImportCsvUploadCommitService service = new ImportCsvUploadCommitService(authorization,
            documentCommit, metadata, transactionManager);

    @Test
    void remainingBudgetBelowOneSecondNeverStartsATransaction() {
        BusinessException expired = assertThrows(BusinessException.class,
                () -> service.commit(TOKEN, null, null, 0));

        assertEquals(503, expired.getCode());
        assertEquals(ImportIngressDeadline.EXCEEDED_MESSAGE_KEY, expired.getMessageKey());
        verifyNoInteractions(transactionManager, authorization, documentCommit, metadata);
    }

    @Test
    void outerTransactionIsRejectedBecauseItWouldIgnoreTheDeadlineTimeout() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.commit(TOKEN, null, null, 30));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verifyNoInteractions(transactionManager, authorization, documentCommit, metadata);
    }

    @Test
    void transactionTimeoutIsTheRemainingBudgetAndFailuresRollBack() {
        // manifest欠落はtoken照合で403となり、transaction内の失敗としてrollbackされる。
        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> service.commit(TOKEN, null, null, 7));

        assertEquals(403, mismatch.getCode());
        verify(transactionManager).getTransaction(argThat((TransactionDefinition definition) ->
                definition.getTimeout() == 7
                        && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED));
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(documentCommit, metadata);
    }

    @Test
    void transactionBeginFailureIsRethrownUnwrappedBecauseNothingWasCommitted() {
        CannotCreateTransactionException poolExhausted =
                new CannotCreateTransactionException("synthetic connection pool exhaustion");
        when(transactionManager.getTransaction(any())).thenThrow(poolExhausted);

        CannotCreateTransactionException failure = assertThrows(CannotCreateTransactionException.class,
                () -> service.commit(TOKEN, MANIFEST, stagedFixture(), 30));

        assertSame(poolExhausted, failure);
        verify(transactionManager, never()).rollback(any());
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(authorization, documentCommit, metadata);
    }

    @Test
    void lockWaitFailureInsideCallbackIsRolledBackAndRethrownUnwrapped() {
        CannotAcquireLockException lockWait = new CannotAcquireLockException("synthetic lock wait timeout");
        when(authorization.requireCurrentAuthorizationForWrite(CONTEXT)).thenThrow(lockWait);

        CannotAcquireLockException failure = assertThrows(CannotAcquireLockException.class,
                () -> service.commit(TOKEN, MANIFEST, stagedFixture(), 30));

        assertSame(lockWait, failure);
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(documentCommit, metadata);
    }

    @Test
    void rollbackFailureIsSuppressedUnderTheOriginalCallbackFailure() {
        QueryTimeoutException writeGateTimeout = new QueryTimeoutException("synthetic write gate timeout");
        TransactionSystemException rollbackFailure = new TransactionSystemException("synthetic rollback failure");
        when(authorization.requireCurrentAuthorizationForWrite(CONTEXT)).thenThrow(writeGateTimeout);
        doThrow(rollbackFailure).when(transactionManager).rollback(any());

        QueryTimeoutException failure = assertThrows(QueryTimeoutException.class,
                () -> service.commit(TOKEN, MANIFEST, stagedFixture(), 30));

        assertSame(writeGateTimeout, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(rollbackFailure, failure.getSuppressed()[0]);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void commitStatementFailureIsReportedAsOutcomeUnknown() {
        StagedImportDocument staged = stagedFixture();
        stubSuccessfulCallback(staged);
        TransactionSystemException ackLoss = new TransactionSystemException("synthetic commit acknowledgement loss");
        doThrow(ackLoss).when(transactionManager).commit(any());

        ImportCommitOutcomeUnknownException failure = assertThrows(ImportCommitOutcomeUnknownException.class,
                () -> service.commit(TOKEN, MANIFEST, staged, 30));

        assertSame(ackLoss, failure.getCause());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void commitFailureThatCompletedAsRollbackIsDefinitelyNotCommitted() {
        StagedImportDocument staged = stagedFixture();
        stubSuccessfulCallback(staged);
        UnexpectedRollbackException rolledBack = new UnexpectedRollbackException("synthetic rollback-only");
        doAnswer(invocation -> {
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            throw rolledBack;
        }).when(transactionManager).commit(any());

        TransactionSynchronizationManager.initSynchronization();
        try {
            UnexpectedRollbackException failure = assertThrows(UnexpectedRollbackException.class,
                    () -> service.commit(TOKEN, MANIFEST, staged, 30));
            assertSame(rolledBack, failure);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void successfulCommitReturnsTheRegisteredJob() {
        StagedImportDocument staged = stagedFixture();
        stubSuccessfulCallback(staged);

        ImportUploadJobDetail result = service.commit(TOKEN, MANIFEST, staged, 30);

        assertEquals(new ImportUploadJobDetail(101, "UPLOADED", "CUSTOMER", SHA.substring(0, 12), 1, "UTF8"), result);
        verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    /** 実commitImportDocumentと同じく、insert時にdocument/versionのIDを採番する。 */
    private void stubSuccessfulCallback(StagedImportDocument staged) {
        when(authorization.requireCurrentAuthorizationForWrite(CONTEXT)).thenReturn(CONTEXT);
        doAnswer(invocation -> {
            staged.document().setId(81L);
            staged.version().setId(91L);
            staged.version().setDocumentId(81L);
            return staged.document();
        }).when(documentCommit).commitImportDocument(staged);
        when(metadata.registerImportJobFromStaged(any(), eq(3L), any(), eq(staged))).thenReturn(101L);
    }

    /** 実stageと同じく、commit前のdocument/versionはID未採番。 */
    private static StagedImportDocument stagedFixture() {
        Document document = new Document();
        document.setTenantId("tenant-test");
        document.setLegalEntityId("41");
        document.setDocumentType("IMPORT");
        DocumentVersion version = new DocumentVersion();
        version.setTenantId("tenant-test");
        version.setVersionNo(1);
        version.setStorageKey("unit-fixed-fixture/object");
        version.setSizeBytes(64L);
        version.setSha256(SHA);
        version.setScanStatus("CLEAN");
        return new StagedImportDocument(document, version, version.getStorageKey(), "unit-fixed-operation");
    }
}
