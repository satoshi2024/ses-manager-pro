package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.impl.DocumentMetadataCommitService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** metadata commitがingress総期限の残り秒数だけをtransaction timeoutにすることを確認する。 */
class ImportCsvUploadCommitServiceTest {
    private static final ImportAdmissionToken TOKEN = new ImportAdmissionToken("tenant-test", 31, 41, 51, 3,
            ImportEntity.CUSTOMER, SesImportV1Schema.VERSION, ImportEncoding.UTF8, "unit-fixed-fixture");

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
}
