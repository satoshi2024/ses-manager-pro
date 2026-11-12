package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentAccessLog;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentAccessLogMapper;
import com.ses.mapper.DocumentHashClaimMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.storage.DocumentStorage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/** CLEAN済みStorageを短い新規transactionで文書台帳へ確定する。 */
@Service
public class DocumentMetadataCommitService {

    private static final java.util.Set<String> HASH_CLAIM_DOCUMENT_TYPES = java.util.Set.of(
            "ORDER_RECEIVED", "ORDER_ACKNOWLEDGEMENT", "ACCEPTANCE");

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentAccessLogMapper documentAccessLogMapper;
    private final DocumentHashClaimMapper documentHashClaimMapper;
    private final DocumentStorage documentStorage;

    public DocumentMetadataCommitService(DocumentMapper documentMapper,
                                         DocumentVersionMapper documentVersionMapper,
                                         DocumentLinkMapper documentLinkMapper,
                                         DocumentAccessLogMapper documentAccessLogMapper,
                                         DocumentHashClaimMapper documentHashClaimMapper,
                                         DocumentStorage documentStorage) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentLinkMapper = documentLinkMapper;
        this.documentAccessLogMapper = documentAccessLogMapper;
        this.documentHashClaimMapper = documentHashClaimMapper;
        this.documentStorage = documentStorage;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DocumentVersion findByIdempotencyKey(String tenantId, String sourceType, String businessKey,
                                                String versionDiscriminator) {
        return documentVersionMapper.findByIdempotencyKey(tenantId, sourceType, businessKey, versionDiscriminator);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Document commitNew(Document document, DocumentVersion version, String targetType, Long targetId) {
        documentMapper.insert(document);
        version.setDocumentId(document.getId());
        claimHash(document, version.getSha256());
        documentVersionMapper.insert(version);
        link(document.getTenantId(), document.getId(), targetType, targetId);
        recordAccess(document.getId(), version.getId(), "REGISTER");
        return document;
    }

    /** raw importだけに使用するsame-transaction metadata insert。一般文書登録経路は変更しない。 */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public Document commitImportDocument(StagedImportDocument staged) {
        if (staged == null || staged.document() == null || staged.version() == null
                || staged.storageKey() == null || !staged.storageKey().equals(staged.version().getStorageKey())
                || staged.operationId() == null || staged.operationId().isBlank()
                || !"IMPORT".equals(staged.document().getDocumentType())
                || !"CLEAN".equals(staged.version().getScanStatus())
                || staged.document().getId() != null || staged.version().getId() != null
                || staged.version().getSizeBytes() == null || staged.version().getSizeBytes() > 64L * 1024 * 1024
                || staged.version().getSha256() == null || !staged.version().getSha256().matches("[0-9a-f]{64}")) {
            throw BusinessException.of(400, "error.import.invalidStagedDocument");
        }
        registerImportStorageRollback(staged.storageKey(), staged.operationId());
        documentMapper.insert(staged.document());
        staged.version().setDocumentId(staged.document().getId());
        documentVersionMapper.insert(staged.version());
        recordAccess(staged.document().getId(), staged.version().getId(), "REGISTER");
        return staged.document();
    }

    private void registerImportStorageRollback(String storageKey, String operationId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("import metadata commitにはtransaction同期が必要です");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    try {
                        documentStorage.delete(storageKey);
                    } catch (Exception failure) {
                        org.slf4j.LoggerFactory.getLogger(DocumentMetadataCommitService.class)
                                .error("[文書台帳] import rollback補償削除失敗: operationId={} storageKey={} exceptionClass={}",
                                        operationId, storageKey, failure.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public DocumentVersion commitVersion(Document document, DocumentVersion version) {
        documentVersionMapper.insert(version);
        if ("CONFIRMED".equals(document.getStatus())) {
            int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                    .eq(Document::getId, document.getId())
                    .eq(Document::getTenantId, document.getTenantId())
                    .eq(Document::getVersion, document.getVersion())
                    .set(Document::getStatus, "AMENDED")
                    .set(Document::getVersion, document.getVersion() + 1));
            if (updated == 0) {
                throw BusinessException.of(409, "error.document.optimisticLock");
            }
        }
        recordAccess(document.getId(), version.getId(), "AMEND");
        return version;
    }

    private void claimHash(Document document, String sha256) {
        if (!HASH_CLAIM_DOCUMENT_TYPES.contains(document.getDocumentType())) {
            return;
        }
        try {
            documentHashClaimMapper.insertClaim(document.getTenantId(), document.getDocumentType(), sha256,
                    document.getId());
        } catch (DataIntegrityViolationException e) {
            if ("ORDER_RECEIVED".equals(document.getDocumentType())) {
                throw BusinessException.of(409, "error.order.duplicateSourceDocument");
            }
            throw BusinessException.of(409, "error.document.duplicateHash");
        }
    }

    private void link(String tenantId, Long documentId, String targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            return;
        }
        DocumentLink existing = documentLinkMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentLink>()
                .eq(DocumentLink::getTenantId, tenantId)
                .eq(DocumentLink::getDocumentId, documentId)
                .eq(DocumentLink::getTargetType, targetType)
                .eq(DocumentLink::getTargetId, targetId));
        if (existing == null) {
            DocumentLink link = new DocumentLink();
            link.setTenantId(tenantId);
            link.setDocumentId(documentId);
            link.setTargetType(targetType);
            link.setTargetId(targetId);
            documentLinkMapper.insert(link);
        }
    }

    private void recordAccess(Long documentId, Long versionId, String action) {
        DocumentAccessLog log = new DocumentAccessLog();
        log.setDocumentId(documentId);
        log.setVersionId(versionId);
        log.setAction(action);
        // portal principalには内部user IDがないため、監査行のNOT NULL制約を満たす固定匿名actorを使用する。
        Long userId = com.ses.common.util.SecurityUtils.currentUserId();
        log.setUserId(userId != null ? userId : -1L);
        log.setOccurredAt(LocalDateTime.now());
        try {
            documentAccessLogMapper.insert(log);
        } catch (Exception e) {
            throw BusinessException.of("文書アクセス監査ログの記録に失敗しました。");
        }
    }
}
