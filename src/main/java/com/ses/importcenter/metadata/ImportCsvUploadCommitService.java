package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.impl.DocumentMetadataCommitService;
import com.ses.service.impl.StagedImportDocument;
import com.ses.common.util.LogRedaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * CLEAN済みstorageとDocument/version/job/linkを一つの短いDB transactionで確定する。
 *
 * <p>transaction timeoutにはingress総期限の残り秒数を渡し、transaction内の各statementへquery timeoutとして
 * 適用させる。ただし接続取得の待機（Hikari connection-timeout）とCOMMIT文自体はこのtimeoutの対象外である。
 *
 * <p>失敗は二種類に分けて呼出元へ返す。
 * <ul>
 *   <li>transaction開始・コールバック内・rollbackの失敗：COMMIT未発行のため未commitが確定している。元の例外をそのまま投げ、
 *       呼出元は本requestのstageを破棄する。</li>
 *   <li>COMMIT処理自体の失敗：DB上の結果が不明なため{@link ImportCommitOutcomeUnknownException}で包む。
 *       ただしCOMMIT処理中にrollbackへ倒れたこと（afterCompletionがROLLED_BACK）を確認できた場合は未commit確定として扱う。</li>
 * </ul>
 */
@Service
public class ImportCsvUploadCommitService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportCsvUploadCommitService.class);
    private static final String TRANSACTION_NAME = "NF06-A1A-raw-upload-commit";
    private final ImportMetadataAuthorizationService authorization;
    private final DocumentMetadataCommitService documentCommit;
    private final ImportMetadataService metadata;
    private final PlatformTransactionManager transactionManager;

    public ImportCsvUploadCommitService(ImportMetadataAuthorizationService authorization,
                                        DocumentMetadataCommitService documentCommit,
                                        ImportMetadataService metadata,
                                        PlatformTransactionManager transactionManager) {
        this.authorization = authorization;
        this.documentCommit = documentCommit;
        this.metadata = metadata;
        this.transactionManager = transactionManager;
    }

    /**
     * @param timeoutSeconds ingress総期限から導出した残り秒数。1未満ならtransactionを開始しない。
     */
    public ImportUploadJobDetail commit(ImportAdmissionToken token, ImportAdmissionManifest contentManifest,
                                        StagedImportDocument staged, int timeoutSeconds) {
        if (timeoutSeconds < 1) {
            throw BusinessException.of(503, ImportIngressDeadline.EXCEEDED_MESSAGE_KEY);
        }
        // 外側transactionへ参加するとtimeoutが無視され総期限が効かないため、単独transactionだけを許す。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("raw upload commitは外側transactionなしで呼び出す必要があります");
        }
        DefaultTransactionDefinition definition =
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED);
        definition.setTimeout(timeoutSeconds);
        definition.setName(TRANSACTION_NAME);
        // 開始失敗（CannotCreateTransactionException等）はtransaction未開始で未commitが確定しているため、そのまま投げる。
        TransactionStatus status = transactionManager.getTransaction(definition);
        CompletionProbe completion = new CompletionProbe();
        ImportUploadJobDetail result;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(completion);
            }
            result = commitInTransaction(token, contentManifest, staged);
        } catch (RuntimeException | Error callbackFailure) {
            // 書込gateのtimeout・lock待ちtimeout・insert前後の例外はCOMMIT未発行。rollback後に元の例外を投げる。
            rollbackAfterCallbackFailure(status, callbackFailure);
            throw callbackFailure;
        }
        try {
            transactionManager.commit(status);
        } catch (RuntimeException | Error commitFailure) {
            if (completion.rolledBack()) {
                // COMMIT処理中にrollbackへ倒れた（beforeCommit失敗・rollback-only等）。未commitが確定している。
                throw commitFailure;
            }
            log.error("[取込] metadataのCOMMIT結果を確定できません。stage自身の行をreadbackして判定します: "
                    + "completionStatus={} failureClass={}", completion.describe(),
                    LogRedaction.exceptionType(commitFailure));
            throw new ImportCommitOutcomeUnknownException(commitFailure);
        }
        return result;
    }

    private void rollbackAfterCallbackFailure(TransactionStatus status, Throwable callbackFailure) {
        try {
            transactionManager.rollback(status);
        } catch (RuntimeException | Error rollbackFailure) {
            // rollbackに失敗してもCOMMITは発行していないため未commitは確定。元の失敗を優先し、rollback失敗はsuppressedで残す。
            callbackFailure.addSuppressed(rollbackFailure);
            log.error("[取込] metadata commit失敗後のrollbackに失敗しました（COMMIT未発行のため未commitは確定）: "
                    + "failureClass={} rollbackFailureClass={}", LogRedaction.exceptionType(callbackFailure),
                    LogRedaction.exceptionType(rollbackFailure));
        }
    }

    /** COMMIT処理の最終状態（afterCompletion）を記録し、COMMIT失敗時の確定/不確定を判別する。 */
    private static final class CompletionProbe implements TransactionSynchronization {
        private static final int NOT_COMPLETED = -1;
        private volatile int status = NOT_COMPLETED;

        @Override
        public void afterCompletion(int completionStatus) {
            status = completionStatus;
        }

        boolean rolledBack() {
            return status == STATUS_ROLLED_BACK;
        }

        String describe() {
            return switch (status) {
                case STATUS_COMMITTED -> "COMMITTED";
                case STATUS_ROLLED_BACK -> "ROLLED_BACK";
                case STATUS_UNKNOWN -> "UNKNOWN";
                default -> "NOT_COMPLETED";
            };
        }
    }

    private ImportUploadJobDetail commitInTransaction(ImportAdmissionToken token,
                                                      ImportAdmissionManifest contentManifest,
                                                      StagedImportDocument staged) {
        ImportAuthorizedContext expected = new ImportAuthorizedContext(token.tenantId(),
                token.actorUserId(), token.legalEntityId());
        ImportAuthorizedContext current = authorization.requireCurrentAuthorizationForWrite(expected);
        requireTokenMatch(token, contentManifest, current, staged);

        documentCommit.commitImportDocument(staged);
        var document = staged.document();
        var version = staged.version();
        ImportAdmissionManifest committedManifest = new ImportAdmissionManifest(contentManifest.fixtureKey(),
                token.tenantId(), token.legalEntityId(), token.sourceSystemId(), token.sourceVersion(),
                ImportEntity.CUSTOMER, SesImportV1Schema.VERSION, token.encoding(), contentManifest.sourceSha256(),
                document.getId(), version.getId(), version.getVersionNo(), version.getSha256(), "A1A_UPLOADED");
        ImportJobRegistrationRequest request = new ImportJobRegistrationRequest(token.sourceSystemId(),
                ImportEntity.CUSTOMER, SesImportV1Schema.VERSION, token.encoding(), contentManifest.sourceSha256(),
                document.getId(), version.getId(), version.getVersionNo());
        long jobId = metadata.registerImportJobFromStaged(request, token.sourceVersion(), committedManifest, staged);
        return new ImportUploadJobDetail(jobId, "UPLOADED", ImportEntity.CUSTOMER.name(),
                contentManifest.sourceSha256().substring(0, 12), version.getVersionNo(), token.encoding().name());
    }

    private static void requireTokenMatch(ImportAdmissionToken token, ImportAdmissionManifest manifest,
                                          ImportAuthorizedContext current, StagedImportDocument staged) {
        if (token == null || manifest == null || staged == null || staged.document() == null
                || staged.version() == null || token.fixtureKey() == null || token.fixtureKey().isBlank()
                || staged.operationId() == null || staged.operationId().isBlank()
                || token.actorUserId() < 1 || token.sourceSystemId() < 1 || token.sourceVersion() < 1
                || token.entity() != ImportEntity.CUSTOMER || !SesImportV1Schema.VERSION.equals(token.schemaVersion())
                || token.encoding() == null
                || !current.tenantId().equals(token.tenantId()) || current.actorUserId() != token.actorUserId()
                || current.legalEntityId() != token.legalEntityId()
                || !manifest.fixtureKey().equals(token.fixtureKey())
                || !manifest.tenantId().equals(token.tenantId()) || manifest.legalEntityId() != token.legalEntityId()
                || manifest.sourceSystemId() != token.sourceSystemId()
                || manifest.sourceVersion() != token.sourceVersion() || manifest.entity() != ImportEntity.CUSTOMER
                || !SesImportV1Schema.VERSION.equals(manifest.schemaVersion()) || manifest.encoding() != token.encoding()
                || !"A1A_CONTENT".equals(manifest.phase())
                || manifest.sourceSha256() == null || !manifest.sourceSha256().matches("[0-9a-f]{64}")
                || !manifest.sourceSha256().equals(staged.version().getSha256())
                || !"IMPORT".equals(staged.document().getDocumentType())
                || !token.tenantId().equals(staged.document().getTenantId())
                || !Long.toString(token.legalEntityId()).equals(staged.document().getLegalEntityId())
                || !staged.storageKey().equals(staged.version().getStorageKey())
                || staged.version().getSizeBytes() == null || staged.version().getSizeBytes() < 1
                || staged.version().getSizeBytes() > 64L * 1024 * 1024
                || !"CLEAN".equals(staged.version().getScanStatus())) {
            throw BusinessException.of(403, "error.import.admissionMismatch");
        }
    }
}
