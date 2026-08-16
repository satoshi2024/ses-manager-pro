package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.dto.document.IntegrityFinding;
import com.ses.entity.Document;
import com.ses.entity.DocumentAccessLog;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentType;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentAccessLogMapper;
import com.ses.mapper.DocumentDisposalRequestMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentTypeMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.DocumentService;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.service.storage.DocumentStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文書台帳サービス実装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentAccessLogMapper documentAccessLogMapper;
    private final DocumentDisposalRequestMapper documentDisposalRequestMapper;
    private final DocumentTypeMapper documentTypeMapper;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final DocumentStorage documentStorage;
    private final ObjectProvider<FileScanner> fileScannerProvider;
    private final com.ses.mapper.SalesOrderMapper salesOrderMapper;
    private final com.ses.mapper.AcceptanceMapper acceptanceMapper;
    private final com.ses.mapper.DocumentHashClaimMapper documentHashClaimMapper;

    private static final String DEFAULT_TENANT_ID = "default";
    private static final Set<String> HASH_CLAIM_DOCUMENT_TYPES = Set.of(
            "ORDER_RECEIVED", "ORDER_ACKNOWLEDGEMENT", "ACCEPTANCE");

    // ----------------------------------------------------------------
    // 登録
    // ----------------------------------------------------------------

    @Override
    @Transactional
    public Document registerGenerated(DocumentRegisterRequest request, InputStream content) {
        return doRegister(request, content);
    }

    @Override
    @Transactional
    public Document registerReceived(DocumentRegisterRequest request, InputStream content) {
        return doRegister(request, content);
    }

    private Document doRegister(DocumentRegisterRequest request, InputStream content) {
        String sourceType = request.getSourceType() != null ? request.getSourceType() : "GENERATED";
        String businessKey = (request.getBusinessKey() != null && !request.getBusinessKey().isBlank())
                ? request.getBusinessKey()
                : sourceType + ":" + UUID.randomUUID();
        String discriminator = (request.getVersionDiscriminator() != null && !request.getVersionDiscriminator().isBlank())
                ? request.getVersionDiscriminator()
                : "v1";

        // 1. 冪等チェック（tenant_id対応）
        DocumentVersion existingVersion = documentVersionMapper.findByIdempotencyKey(
                DEFAULT_TENANT_ID, sourceType, businessKey, discriminator);
        if (existingVersion != null) {
            log.info("[文書台帳] 冪等登録: sourceType={} businessKey={} discriminator={} → 既存documentId={}",
                    sourceType, businessKey, discriminator, existingVersion.getDocumentId());
            return documentMapper.selectById(existingVersion.getDocumentId());
        }

        // 2. 一時ファイルへストリーミング書き出し＆SHA-256ハッシュ算出（固定ヒープ化）
        String storageKey = generateStorageKey();
        StreamDigestResult streamResult = writeToTempAndDigest(content);

        // 3. スキャン処理（未知/失敗/感染はStorageへ保存する前にfail-closed）
        FileScanResult scanResult = scanQuarantinedPath(streamResult.tempPath());
        if (scanResult == null || scanResult.status() != FileScanResult.Status.CLEAN) {
            deleteTempFile(streamResult.tempPath());
            log.warn("[文書台帳] スキャン失敗・感染のため登録を拒否します: result={}", scanResult);
            throw BusinessException.of(400, "error.file.scanRejected");
        }

        try {
            // 4. DB tx: document作成後にHashをアトミックClaimする。
            Document doc = buildDocument(request);
            documentMapper.insert(doc);
            claimHashIfRequired(doc, streamResult.sha256());

            // 5. put前にrollback補償を登録する。putが部分書込みで失敗しても
            // transaction中のcatchで即時削除し、rollback後にも冪等削除する。
            registerStorageRollbackCompensation(storageKey);
            // Claim成功後だけStorageのquarantine領域へ保存する。
            try (InputStream tempIs = Files.newInputStream(streamResult.tempPath())) {
                documentStorage.put(storageKey, tempIs, true);
            }

            // 6. version・業務リンクを保存する。
            DocumentVersion version = buildVersion(request, doc.getId(), storageKey, streamResult.sizeBytes(), streamResult.sha256());
            version.setBusinessKey(businessKey);
            version.setVersionDiscriminator(discriminator);
            version.setScanStatus("CLEAN");
            // portal等の内部ログインuser以外からの登録時は作成者を明示指定できる（NOT NULL列対応）
            if (request.getCreatedBy() != null) {
                version.setCreatedBy(request.getCreatedBy());
            }
            documentVersionMapper.insert(version);

            if (request.getTargetType() != null && request.getTargetId() != null) {
                link(doc.getId(), request.getTargetType(), request.getTargetId());
            }

            // 7. storage promote
            documentStorage.promote(storageKey);

            // 8. アクセスログ
            recordAccessLog(doc.getId(), version.getId(), "REGISTER");

            log.info("[文書台帳] 登録完了: documentId={} versionId={} sha256={}", doc.getId(), version.getId(), streamResult.sha256());
            return doc;

        } catch (Exception e) {
            log.error("[文書台帳] DB保存失敗。storageKey={} error={}", storageKey, e.getMessage());
            cleanupStorageAfterFailure(storageKey);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("文書登録に失敗しました", e);
        } finally {
            deleteTempFile(streamResult.tempPath());
        }
    }

    // ----------------------------------------------------------------
    // 版追加
    // ----------------------------------------------------------------

    @Override
    @Transactional
    public DocumentVersion addVersion(Long documentId, DocumentRegisterRequest request, InputStream content) {
        Document doc = getDocumentOrThrow(documentId);

        String sourceType = request.getSourceType() != null ? request.getSourceType() : "RECEIVED";
        String businessKey = (request.getBusinessKey() != null && !request.getBusinessKey().isBlank())
                ? request.getBusinessKey()
                : sourceType + ":" + documentId + ":" + UUID.randomUUID();
        String discriminator = (request.getVersionDiscriminator() != null && !request.getVersionDiscriminator().isBlank())
                ? request.getVersionDiscriminator()
                : "v" + System.currentTimeMillis();

        DocumentVersion existing = documentVersionMapper.findByIdempotencyKey(
                DEFAULT_TENANT_ID, sourceType, businessKey, discriminator);
        if (existing != null) {
            log.info("[文書台帳] 冪等addVersion: 既存versionId={}", existing.getId());
            return existing;
        }

        String storageKey = generateStorageKey();
        StreamDigestResult streamResult = writeToTempAndDigest(content);

        // fail-closed スキャン
        FileScanResult scanResult = scanQuarantinedPath(streamResult.tempPath());
        if (scanResult == null || scanResult.status() != FileScanResult.Status.CLEAN) {
            deleteTempFile(streamResult.tempPath());
            throw BusinessException.of(400, "error.file.scanRejected");
        }

        try {
            claimHashIfRequired(doc, streamResult.sha256());
            registerStorageRollbackCompensation(storageKey);
            try (InputStream tempIs = Files.newInputStream(streamResult.tempPath())) {
                documentStorage.put(storageKey, tempIs, true);
            }

            DocumentVersion version = buildVersion(request, documentId, storageKey, streamResult.sizeBytes(), streamResult.sha256());
            version.setBusinessKey(businessKey);
            version.setVersionDiscriminator(discriminator);
            version.setScanStatus("CLEAN");
            if (request.getCreatedBy() != null) {
                version.setCreatedBy(request.getCreatedBy());
            }
            documentVersionMapper.insert(version);

            if ("CONFIRMED".equals(doc.getStatus())) {
                int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                        .eq(Document::getId, documentId)
                        .eq(Document::getVersion, doc.getVersion())
                        .set(Document::getStatus, "AMENDED")
                        .set(Document::getVersion, doc.getVersion() + 1));
                if (updated == 0) {
                    throw BusinessException.of(409, "error.document.optimisticLock");
                }
            }

            documentStorage.promote(storageKey);
            recordAccessLog(documentId, version.getId(), "AMEND");

            log.info("[文書台帳] 版追加完了: documentId={} newVersionId={}", documentId, version.getId());
            return version;

        } catch (Exception e) {
            log.error("[文書台帳] 版追加DB失敗。storageKey={} error={}", storageKey, e.getMessage());
            cleanupStorageAfterFailure(storageKey);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("文書版追加に失敗しました", e);
        } finally {
            deleteTempFile(streamResult.tempPath());
        }
    }

    private void claimHashIfRequired(Document doc, String sha256) {
        if (doc == null || doc.getDocumentType() == null
                || !HASH_CLAIM_DOCUMENT_TYPES.contains(doc.getDocumentType())) {
            return;
        }
        String tenantId = doc.getTenantId() != null && !doc.getTenantId().isBlank()
                ? doc.getTenantId() : DEFAULT_TENANT_ID;
        try {
            documentHashClaimMapper.insertClaim(tenantId, doc.getDocumentType(), sha256, doc.getId());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if ("ORDER_RECEIVED".equals(doc.getDocumentType())) {
                throw BusinessException.of(409, "error.order.duplicateSourceDocument");
            }
            throw BusinessException.of(409, "error.document.duplicateHash");
        }
    }

    private void registerStorageRollbackCompensation(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    try {
                        documentStorage.delete(storageKey);
                    } catch (Exception e) {
                        log.error("[文書台帳] rollback補償削除失敗: storageKey={}", storageKey, e);
                    }
                }
            }
        });
    }

    private void cleanupStorageAfterFailure(String storageKey) {
        // transaction中でも部分put/promoteの実体はDB rollbackの対象外なので、
        // catch時に即時削除する。afterCompletionの補償削除は冪等な二重保険として残す。
        try {
            documentStorage.delete(storageKey);
        } catch (Exception cleanupError) {
            log.error("[文書台帳] 例外時のStorage補償削除失敗: storageKey={}", storageKey, cleanupError);
        }
    }

    // ----------------------------------------------------------------
    // リンク / 法的hold / 廃棄 / 他
    // ----------------------------------------------------------------

    @Override
    @Transactional
    public void link(Long documentId, String targetType, Long targetId) {
        getDocumentOrThrow(documentId);
        DocumentLink existing = documentLinkMapper.selectOne(new LambdaQueryWrapper<DocumentLink>()
                .eq(DocumentLink::getDocumentId, documentId)
                .eq(DocumentLink::getTargetType, targetType)
                .eq(DocumentLink::getTargetId, targetId));
        if (existing == null) {
            DocumentLink link = new DocumentLink();
            link.setDocumentId(documentId);
            link.setTargetType(targetType);
            link.setTargetId(targetId);
            documentLinkMapper.insert(link);
        }
    }

    @Override
    @Transactional
    public void placeLegalHold(Long documentId, boolean hold, String reason) {
        Document doc = getDocumentOrThrow(documentId);
        int newFlag = hold ? 1 : 0;
        if (doc.getLegalHoldFlag() == newFlag) {
            return;
        }
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .eq(Document::getVersion, doc.getVersion())
                .set(Document::getLegalHoldFlag, newFlag)
                .set(Document::getVersion, doc.getVersion() + 1));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.optimisticLock");
        }
        recordAccessLog(documentId, null, hold ? "LEGAL_HOLD" : "LEGAL_HOLD_RELEASE");
        log.info("[文書台帳] legal hold {}. documentId={} reason={}", hold ? "設定" : "解除", documentId, reason);
    }

    @Override
    @Transactional
    public DocumentDisposalRequest requestDisposal(Long documentId, String reason) {
        Document doc = getDocumentOrThrow(documentId);

        if (doc.getLegalHoldFlag() != null && doc.getLegalHoldFlag() == 1) {
            throw BusinessException.of(400, "error.document.legalHoldActive");
        }
        if (doc.getRetentionUntil() == null) {
            throw BusinessException.of(400, "error.document.retentionUndetermined");
        }
        if (!List.of("CONFIRMED", "AMENDED", "CANCELLED").contains(doc.getStatus())) {
            throw BusinessException.of(400, "error.document.notDisposable");
        }

        Long currentUserId = SecurityUtils.currentUserId();

        DocumentDisposalRequest req = new DocumentDisposalRequest();
        req.setDocumentId(documentId);
        req.setRequestedBy(currentUserId);
        req.setStatus("PENDING");
        req.setReason(reason);
        documentDisposalRequestMapper.insert(req);

        recordAccessLog(documentId, null, "DISPOSE_REQUEST");
        log.info("[文書台帳] 廃棄申請: documentId={} requestedBy={}", documentId, currentUserId);
        return req;
    }

    private void assertAdminUser() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_管理者".equals(a.getAuthority()));
        if (!isAdmin) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    @Override
    @Transactional
    public void approveDisposal(Long disposalRequestId) {
        assertAdminUser();
        DocumentDisposalRequest req = getDisposalRequestOrThrow(disposalRequestId);
        if (!"PENDING".equals(req.getStatus())) {
            throw BusinessException.of(400, "error.document.disposalNotPending");
        }

        Long currentUserId = SecurityUtils.currentUserId();
        if (currentUserId != null && currentUserId.equals(req.getRequestedBy())) {
            throw BusinessException.of(400, "error.document.disposalSelfApproval");
        }

        int updated = documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, disposalRequestId)
                .eq(DocumentDisposalRequest::getStatus, "PENDING")
                .set(DocumentDisposalRequest::getStatus, "APPROVED")
                .set(DocumentDisposalRequest::getApprovedBy, currentUserId)
                .set(DocumentDisposalRequest::getApprovedAt, LocalDateTime.now()));

        if (updated == 0) {
            throw BusinessException.of(409, "error.document.disposalConcurrentUpdate");
        }

        recordAccessLog(req.getDocumentId(), null, "DISPOSE_APPROVE");
        log.info("[文書台帳] 廃棄申請を承認しました: requestId={} approvedBy={}", disposalRequestId, currentUserId);
    }

    @Override
    @Transactional
    public void rejectDisposal(Long disposalRequestId, String reason) {
        assertAdminUser();
        DocumentDisposalRequest req = getDisposalRequestOrThrow(disposalRequestId);
        if (!"PENDING".equals(req.getStatus())) {
            throw BusinessException.of(400, "error.document.disposalNotPending");
        }

        Long currentUserId = SecurityUtils.currentUserId();
        int updated = documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, disposalRequestId)
                .eq(DocumentDisposalRequest::getStatus, "PENDING")
                .set(DocumentDisposalRequest::getStatus, "REJECTED")
                .set(DocumentDisposalRequest::getReason, (req.getReason() != null ? req.getReason() + " | 却下理由: " : "却下理由: ") + reason)
                .set(DocumentDisposalRequest::getApprovedBy, currentUserId)
                .set(DocumentDisposalRequest::getApprovedAt, LocalDateTime.now()));

        if (updated == 0) {
            throw BusinessException.of(409, "error.document.disposalConcurrentUpdate");
        }

        recordAccessLog(req.getDocumentId(), null, "DISPOSE_REJECT");
        log.info("[文書台帳] 廃棄申請を却下しました: requestId={} rejectedBy={}", disposalRequestId, currentUserId);
    }

    @Override
    @Transactional
    public void executeDisposal(Long disposalRequestId) {
        assertAdminUser();
        DocumentDisposalRequest req = getDisposalRequestOrThrow(disposalRequestId);
        if (!"APPROVED".equals(req.getStatus())) {
            throw BusinessException.of(400, "error.document.disposalNotApproved");
        }

        Document doc = getDocumentOrThrow(req.getDocumentId());
        if (doc.getLegalHoldFlag() != null && doc.getLegalHoldFlag() == 1) {
            throw BusinessException.of(400, "error.document.legalHoldActive");
        }
        if ("DISPOSED".equals(doc.getStatus())) {
            return;
        }

        List<DocumentVersion> versions = documentVersionMapper.findByDocumentId(req.getDocumentId());

        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, req.getDocumentId())
                .set(Document::getStatus, "DISPOSED"));
        documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, disposalRequestId)
                .set(DocumentDisposalRequest::getStatus, "DISPOSED")
                .set(DocumentDisposalRequest::getDisposedAt, LocalDateTime.now()));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    boolean allSuccess = true;
                    List<String> failedKeys = new ArrayList<>();
                    for (DocumentVersion v : versions) {
                        try {
                            documentStorage.delete(v.getStorageKey());
                        } catch (Exception e) {
                            allSuccess = false;
                            failedKeys.add(v.getStorageKey());
                            log.error("[文書台帳] Storage削除失敗: key={} error={}", v.getStorageKey(), e.getMessage());
                        }
                    }
                    if (!allSuccess) {
                        log.error("[文書台帳] 一部Storageの削除に失敗しました: documentId={} failedKeys={}",
                                req.getDocumentId(), failedKeys);
                        documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                                .eq(DocumentDisposalRequest::getId, disposalRequestId)
                                .set(DocumentDisposalRequest::getStatus, "FAILED")
                                .set(DocumentDisposalRequest::getReason, (req.getReason() != null ? req.getReason() + " | " : "") + "Storage物理削除失敗: " + String.join(", ", failedKeys)));
                    }
                }
            });
        } else {
            List<String> failedKeys = new ArrayList<>();
            for (DocumentVersion v : versions) {
                try {
                    documentStorage.delete(v.getStorageKey());
                } catch (Exception e) {
                    failedKeys.add(v.getStorageKey());
                }
            }
            if (!failedKeys.isEmpty()) {
                documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                        .eq(DocumentDisposalRequest::getId, disposalRequestId)
                        .set(DocumentDisposalRequest::getStatus, "FAILED")
                        .set(DocumentDisposalRequest::getReason, (req.getReason() != null ? req.getReason() + " | " : "") + "Storage物理削除失敗: " + String.join(", ", failedKeys)));
            }
        }

        recordAccessLog(req.getDocumentId(), null, "DISPOSE");
        log.info("[文書台帳] 廃棄実行指示完了: documentId={}", req.getDocumentId());
    }

    @Override
    public List<IntegrityFinding> verifyIntegrity(Long documentId) {
        List<IntegrityFinding> findings = new ArrayList<>();
        List<DocumentVersion> versions = documentVersionMapper.findByDocumentId(documentId);

        for (DocumentVersion v : versions) {
            try (InputStream is = documentStorage.open(v.getStorageKey())) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
                String actualSha256 = HexFormat.of().formatHex(md.digest());

                if (!v.getSha256().equals(actualSha256)) {
                    findings.add(IntegrityFinding.builder()
                            .documentId(documentId)
                            .versionId(v.getId())
                            .storageKey(v.getStorageKey())
                            .expectedSha256(v.getSha256())
                            .actualSha256(actualSha256)
                            .findingType("HASH_MISMATCH")
                            .message("SHA-256不一致: DBの期待値と実体が異なります")
                            .build());
                }
            } catch (Exception e) {
                findings.add(IntegrityFinding.builder()
                        .documentId(documentId)
                        .versionId(v.getId())
                        .storageKey(v.getStorageKey())
                        .expectedSha256(v.getSha256())
                        .findingType("STORAGE_MISSING")
                        .message("Storageに実体が見つかりません: " + e.getMessage())
                        .build());
            }
        }
        return findings;
    }

    @Override
    @Transactional
    public void confirm(Long documentId) {
        Document doc = getDocumentOrThrow(documentId);
        if (!"DRAFT".equals(doc.getStatus())) {
            throw BusinessException.of(400, "error.document.notDraft");
        }

        LocalDate retentionUntil = computeRetentionUntil(doc);

        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .eq(Document::getVersion, doc.getVersion())
                .set(Document::getStatus, "CONFIRMED")
                .set(Document::getRetentionUntil, retentionUntil)
                .set(Document::getVersion, doc.getVersion() + 1));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.optimisticLock");
        }
        log.info("[文書台帳] 確定: documentId={} retentionUntil={}", documentId, retentionUntil);
    }

    @Override
    public InputStream download(Long documentId, Integer versionNo) {
        DocumentVersion version;
        if (versionNo == null) {
            version = documentVersionMapper.findLatestByDocumentId(documentId);
        } else {
            version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                    .eq(DocumentVersion::getDocumentId, documentId)
                    .eq(DocumentVersion::getVersionNo, versionNo));
        }
        if (version == null) {
            throw BusinessException.of(404, "error.document.versionNotFound");
        }
        String scanStatus = version.getScanStatus();
        if (scanStatus == null || !"CLEAN".equals(scanStatus)) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }

        recordAccessLog(documentId, version.getId(), "DOWNLOAD");
        return documentStorage.open(version.getStorageKey());
    }

    // ----------------------------------------------------------------
    // 内部ユーティリティ
    // ----------------------------------------------------------------

    private Document getDocumentOrThrow(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BusinessException.of(404, "error.document.notFound");
        }
        return doc;
    }

    private DocumentDisposalRequest getDisposalRequestOrThrow(Long id) {
        DocumentDisposalRequest req = documentDisposalRequestMapper.selectById(id);
        if (req == null) {
            throw BusinessException.of(404, "error.document.disposalNotFound");
        }
        return req;
    }

    private Document buildDocument(DocumentRegisterRequest request) {
        Document doc = new Document();
        doc.setTenantId(DEFAULT_TENANT_ID);
        doc.setDocumentType(request.getDocumentType());
        doc.setDocumentNo(request.getDocumentNo());
        doc.setTitle(request.getTitle());
        doc.setCounterpartyType(request.getCounterpartyType());
        doc.setCounterpartyId(request.getCounterpartyId());
        doc.setCounterpartyNameSnapshot(request.getCounterpartyNameSnapshot());
        doc.setTransactionDate(request.getTransactionDate());
        doc.setAmount(request.getAmount());
        doc.setCurrency("JPY");
        doc.setDirection(request.getDirection() != null ? request.getDirection() : "OUTGOING");
        doc.setStatus("DRAFT");
        doc.setLegalHoldFlag(0);
        doc.setVersion(1L);
        return doc;
    }

    private DocumentVersion buildVersion(DocumentRegisterRequest request, Long documentId,
                                          String storageKey, long sizeBytes, String sha256) {
        DocumentVersion latest = documentVersionMapper.findLatestByDocumentId(documentId);
        int nextVersionNo = (latest == null) ? 1 : latest.getVersionNo() + 1;

        DocumentVersion v = new DocumentVersion();
        v.setTenantId(DEFAULT_TENANT_ID);
        v.setDocumentId(documentId);
        v.setVersionNo(nextVersionNo);
        v.setStorageKey(storageKey);
        v.setOriginalName(request.getOriginalName() != null ? request.getOriginalName() : "document");
        v.setContentType(request.getContentType());
        v.setSizeBytes(sizeBytes);
        v.setSha256(sha256);
        v.setSourceType(request.getSourceType());
        v.setExternalId(request.getExternalId());
        v.setChangeReason(request.getChangeReason());
        return v;
    }

    /**
     * 法定保存期限算出。
     * 起算日が明確に確定できない場合は null を返し、勝手に LocalDate.now() へフォールバックしない。
     */
    LocalDate computeRetentionUntil(Document doc) {
        DocumentType docType = documentTypeMapper.selectOne(
                new LambdaQueryWrapper<DocumentType>().eq(DocumentType::getCode, doc.getDocumentType()));
        if (docType == null || docType.getRetentionYears() == null) {
            return null;
        }

        LocalDate startDate = null;
        String rule = docType.getRetentionStartRule();
        if ("TRANSACTION_DATE".equals(rule)) {
            startDate = doc.getTransactionDate();
        }
        if (startDate == null) {
            return null;
        }
        return startDate.plusYears(docType.getRetentionYears());
    }

    private record StreamDigestResult(Path tempPath, long sizeBytes, String sha256) {}

    private StreamDigestResult writeToTempAndDigest(InputStream input) {
        try {
            Path temp = Files.createTempFile("doc-upload-", ".tmp");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (DigestInputStream dis = new DigestInputStream(new BufferedInputStream(input), md)) {
                size = Files.copy(dis, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = HexFormat.of().formatHex(md.digest());
            return new StreamDigestResult(temp, size, sha256);
        } catch (Exception e) {
            throw new RuntimeException("アップロードストリーム書込・ハッシュ計算失敗", e);
        }
    }

    private FileScanResult scanQuarantinedPath(Path path) {
        FileScanner scanner = fileScannerProvider.getIfAvailable();
        if (scanner == null) {
            log.warn("[文書台帳] FileScannerが未配線のためfail-closedで感染扱いとします");
            return FileScanResult.infected("scanner-unavailable");
        }
        try {
            return scanner.scan(path, com.ses.common.enums.FileKind.SKILL_SHEET);
        } catch (Exception e) {
            log.warn("[文書台帳] スキャン実行中例外: {}", e.getMessage());
            return FileScanResult.unavailable(e.getMessage());
        }
    }

    private void deleteTempFile(Path temp) {
        try {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        } catch (Exception ignored) {}
    }

    private void recordAccessLog(Long documentId, Long versionId, String action) {
        try {
            DocumentAccessLog log = new DocumentAccessLog();
            log.setDocumentId(documentId);
            log.setVersionId(versionId);
            log.setAction(action);
            log.setUserId(SecurityUtils.currentUserId() != null ? SecurityUtils.currentUserId() : -1L);
            log.setOccurredAt(LocalDateTime.now());
            documentAccessLogMapper.insert(log);
        } catch (Exception e) {
            log.warn("[文書台帳] アクセスログ記録失敗: documentId={} action={} error={}", documentId, action, e.getMessage());
        }
    }

    private static String generateStorageKey() {
        return UUID.randomUUID().toString().replace("-", "") + "/" + UUID.randomUUID();
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.document.DocumentListDTO> searchDocuments(com.ses.dto.document.DocumentSearchQuery query) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Document> pageParam =
                com.ses.common.util.PageUtils.safePage(query.getPage(), query.getPageSize(), 1000L);

        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getTenantId, DEFAULT_TENANT_ID);

        applyDataScopeFilter(wrapper);

        if (query.getDocumentType() != null && !query.getDocumentType().isBlank()) {
            wrapper.eq(Document::getDocumentType, query.getDocumentType());
        }
        if (query.getCounterpartyName() != null && !query.getCounterpartyName().isBlank()) {
            wrapper.like(Document::getCounterpartyNameSnapshot, query.getCounterpartyName().trim());
        }
        if (query.getStartDate() != null) {
            wrapper.ge(Document::getTransactionDate, query.getStartDate());
        }
        if (query.getEndDate() != null) {
            wrapper.le(Document::getTransactionDate, query.getEndDate());
        }
        if (query.getMinAmount() != null) {
            wrapper.ge(Document::getAmount, query.getMinAmount());
        }
        if (query.getMaxAmount() != null) {
            wrapper.le(Document::getAmount, query.getMaxAmount());
        }
        if (query.getDirection() != null && !query.getDirection().isBlank()) {
            wrapper.eq(Document::getDirection, query.getDirection());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(Document::getStatus, query.getStatus());
        }
        if (query.getLegalHoldFlag() != null) {
            wrapper.eq(Document::getLegalHoldFlag, query.getLegalHoldFlag());
        }

        wrapper.orderByDesc(Document::getId);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Document> pageResult = documentMapper.selectPage(pageParam, wrapper);

        // N+1 対策: 全DocumentTypeをキャッシュ
        Map<String, String> typeNameMap = documentTypeMapper.selectList(new LambdaQueryWrapper<DocumentType>())
                .stream().collect(Collectors.toMap(DocumentType::getCode, DocumentType::getName, (a, b) -> a));

        List<com.ses.dto.document.DocumentListDTO> dtoList = new ArrayList<>();
        for (Document doc : pageResult.getRecords()) {
            DocumentVersion latestVersion = documentVersionMapper.findLatestByDocumentId(doc.getId());
            String typeName = typeNameMap.getOrDefault(doc.getDocumentType(), doc.getDocumentType());

            com.ses.dto.document.DocumentListDTO dto = com.ses.dto.document.DocumentListDTO.builder()
                    .id(doc.getId())
                    .tenantId(doc.getTenantId())
                    .documentType(doc.getDocumentType())
                    .documentTypeName(typeName)
                    .documentNo(doc.getDocumentNo())
                    .title(doc.getTitle())
                    .counterpartyType(doc.getCounterpartyType())
                    .counterpartyNameSnapshot(doc.getCounterpartyNameSnapshot())
                    .transactionDate(doc.getTransactionDate())
                    .amount(doc.getAmount())
                    .currency(doc.getCurrency())
                    .direction(doc.getDirection())
                    .status(doc.getStatus())
                    .retentionUntil(doc.getRetentionUntil())
                    .legalHoldFlag(doc.getLegalHoldFlag())
                    .version(doc.getVersion())
                    .latestVersionNo(latestVersion != null ? latestVersion.getVersionNo() : null)
                    .latestOriginalName(latestVersion != null ? latestVersion.getOriginalName() : null)
                    .latestSizeBytes(latestVersion != null ? latestVersion.getSizeBytes() : null)
                    .latestSha256(latestVersion != null ? latestVersion.getSha256() : null)
                    .createdAt(doc.getCreatedAt())
                    .build();
            dtoList.add(dto);
        }

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.document.DocumentListDTO> dtoPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageResult.getCurrent(), pageResult.getSize(), pageResult.getTotal());
        dtoPage.setRecords(dtoList);
        return dtoPage;
    }

    @Override
    public com.ses.dto.document.DocumentDetailDTO getDocumentDetail(Long documentId) {
        Document doc = getDocumentOrThrow(documentId);
        assertDocumentAccessAllowed(doc);
        DocumentType type = documentTypeMapper.selectOne(new LambdaQueryWrapper<DocumentType>().eq(DocumentType::getCode, doc.getDocumentType()));

        List<DocumentVersion> versionEntities = documentVersionMapper.findByDocumentId(documentId);
        List<com.ses.dto.document.DocumentVersionDTO> versionDTOs = new ArrayList<>();
        for (DocumentVersion v : versionEntities) {
            versionDTOs.add(com.ses.dto.document.DocumentVersionDTO.builder()
                    .id(v.getId())
                    .documentId(v.getDocumentId())
                    .versionNo(v.getVersionNo())
                    .storageKey(null)
                    .originalName(v.getOriginalName())
                    .contentType(v.getContentType())
                    .sizeBytes(v.getSizeBytes())
                    .sha256(v.getSha256())
                    .sourceType(v.getSourceType())
                    .businessKey(v.getBusinessKey())
                    .versionDiscriminator(v.getVersionDiscriminator())
                    .externalId(v.getExternalId())
                    .scanStatus(v.getScanStatus())
                    .changeReason(v.getChangeReason())
                    .createdBy(v.getCreatedBy())
                    .createdAt(v.getCreatedAt())
                    .build());
        }

        List<DocumentLink> linkEntities = documentLinkMapper.selectList(new LambdaQueryWrapper<DocumentLink>().eq(DocumentLink::getDocumentId, documentId));
        List<com.ses.dto.document.DocumentLinkDTO> linkDTOs = new ArrayList<>();
        for (DocumentLink l : linkEntities) {
            linkDTOs.add(com.ses.dto.document.DocumentLinkDTO.builder()
                    .id(l.getId())
                    .documentId(l.getDocumentId())
                    .targetType(l.getTargetType())
                    .targetId(l.getTargetId())
                    .createdAt(l.getCreatedAt())
                    .build());
        }

        return com.ses.dto.document.DocumentDetailDTO.builder()
                .id(doc.getId())
                .tenantId(doc.getTenantId())
                .documentType(doc.getDocumentType())
                .documentTypeName(type != null ? type.getName() : doc.getDocumentType())
                .documentNo(doc.getDocumentNo())
                .title(doc.getTitle())
                .counterpartyType(doc.getCounterpartyType())
                .counterpartyId(doc.getCounterpartyId())
                .counterpartyNameSnapshot(doc.getCounterpartyNameSnapshot())
                .transactionDate(doc.getTransactionDate())
                .amount(doc.getAmount())
                .currency(doc.getCurrency())
                .direction(doc.getDirection())
                .status(doc.getStatus())
                .retentionUntil(doc.getRetentionUntil())
                .legalHoldFlag(doc.getLegalHoldFlag())
                .version(doc.getVersion())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .versions(versionDTOs)
                .links(linkDTOs)
                .build();
    }

    public void assertDocumentAccessAllowed(Document doc) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_管理者".equals(a.getAuthority()));
        if (isAdmin) {
            return;
        }

        boolean isHr = com.ses.common.util.SecurityUtils.isHrRole();
        if (isHr) {
            if (Set.of("ORDER_RECEIVED", "ORDER_ACKNOWLEDGEMENT", "ACCEPTANCE").contains(doc.getDocumentType())) {
                throw BusinessException.of(403, "error.forbidden");
            }
        }

        if ("ACCEPTANCE".equals(doc.getDocumentType()) && acceptanceMapper != null) {
            com.ses.entity.Acceptance acceptance = acceptanceMapper.selectOne(
                    new LambdaQueryWrapper<com.ses.entity.Acceptance>().eq(com.ses.entity.Acceptance::getDocumentId, doc.getId()));
            if (acceptance != null) {
                java.time.LocalDate monthEnd = acceptance.getWorkMonth() != null && !acceptance.getWorkMonth().isBlank()
                        ? java.time.YearMonth.parse(acceptance.getWorkMonth()).atEndOfMonth()
                        : java.time.LocalDate.now();
                Set<Long> allowedContracts = dataScopeService.allowedContractIdsAsOf(monthEnd);
                if (allowedContracts != null && !allowedContracts.contains(acceptance.getContractId())) {
                    throw BusinessException.of(403, "error.forbidden");
                }
            }
        }

        List<DocumentLink> links = documentLinkMapper.selectList(
                new LambdaQueryWrapper<DocumentLink>().eq(DocumentLink::getDocumentId, doc.getId()));
        if (links.isEmpty()) {
            throw BusinessException.of(403, "error.forbidden");
        }

        boolean anyAllowed = false;
        for (DocumentLink link : links) {
            try {
                String type = link.getTargetType();
                Long targetId = link.getTargetId();
                if ("CUSTOMER".equals(type)) {
                    dataScopeService.assertAllowedCustomer(targetId);
                    anyAllowed = true;
                    break;
                } else if ("ENGINEER".equals(type)) {
                    dataScopeService.assertAllowedEngineer(targetId);
                    anyAllowed = true;
                    break;
                } else if ("CONTRACT".equals(type)) {
                    if ("ACCEPTANCE".equals(doc.getDocumentType()) && acceptanceMapper != null) {
                        com.ses.entity.Acceptance acc = acceptanceMapper.selectOne(
                                new LambdaQueryWrapper<com.ses.entity.Acceptance>().eq(com.ses.entity.Acceptance::getDocumentId, doc.getId()));
                        if (acc != null) {
                            java.time.LocalDate monthEnd = acc.getWorkMonth() != null && !acc.getWorkMonth().isBlank()
                                    ? java.time.YearMonth.parse(acc.getWorkMonth()).atEndOfMonth()
                                    : java.time.LocalDate.now();
                            Set<Long> allowedContracts = dataScopeService.allowedContractIdsAsOf(monthEnd);
                            if (allowedContracts != null && allowedContracts.contains(acc.getContractId())) {
                                anyAllowed = true;
                                break;
                            } else {
                                throw BusinessException.of(403, "error.forbidden");
                            }
                        }
                    }
                    dataScopeService.assertAllowedContract(targetId);
                    anyAllowed = true;
                    break;
                } else if ("PROJECT".equals(type)) {
                    dataScopeService.assertAllowedProject(targetId);
                    anyAllowed = true;
                    break;
                } else if ("PROPOSAL".equals(type)) {
                    dataScopeService.assertAllowedProposal(targetId);
                    anyAllowed = true;
                    break;
                } else if ("SALES_ORDER".equals(type)) {
                    if (isHr) {
                        throw BusinessException.of(403, "error.forbidden");
                    }
                    // 注文書原本・注文請書は注文一覧と同じscope（顧客DataScope）で見せる
                    com.ses.entity.SalesOrder salesOrder = salesOrderMapper == null ? null
                            : salesOrderMapper.selectById(targetId);
                    if (salesOrder != null) {
                        dataScopeService.assertAllowedCustomer(salesOrder.getCustomerId());
                        anyAllowed = true;
                        break;
                    }
                }
                // 未対応・未定義のターゲットタイプはスキップ（fail-closed）
            } catch (BusinessException ignored) {
                // 次のリンクを試行
            }
        }
        if (!anyAllowed) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    public void applyDataScopeFilter(LambdaQueryWrapper<Document> wrapper) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_管理者".equals(a.getAuthority()));
        if (isAdmin) {
            return;
        }

        boolean isHr = com.ses.common.util.SecurityUtils.isHrRole();
        if (isHr) {
            wrapper.notIn(Document::getDocumentType, "ORDER_RECEIVED", "ORDER_ACKNOWLEDGEMENT", "ACCEPTANCE");
            if (!dataScopeService.isScoped()) {
                return;
            }
        } else {
            if (!dataScopeService.isScoped()) {
                return;
            }
        }

        // 非管理者の場合: 許可されたターゲットID集合により SQL レベルで絞り込み
        Set<Long> allowedCustomers = dataScopeService.allowedCustomerIds();
        Set<Long> allowedEngineers = dataScopeService.allowedEngineerIds();
        Set<Long> allowedContracts = dataScopeService.allowedContractIds();
        Set<Long> allowedProjects = dataScopeService.allowedProjectIds();
        Set<Long> allowedProposals = dataScopeService.allowedProposalIds();

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocumentLink> linkWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        boolean hasCondition = false;

        if (allowedCustomers != null && !allowedCustomers.isEmpty()) {
            linkWrapper.or(w -> w.eq("target_type", "CUSTOMER").in("target_id", allowedCustomers));
            hasCondition = true;
        }
        if (allowedEngineers != null && !allowedEngineers.isEmpty()) {
            linkWrapper.or(w -> w.eq("target_type", "ENGINEER").in("target_id", allowedEngineers));
            hasCondition = true;
        }
        if (allowedContracts != null && !allowedContracts.isEmpty()) {
            // ACCEPTANCE文書以外は契約の現在DataScopeを適用
            linkWrapper.or(w -> w.eq("target_type", "CONTRACT").in("target_id", allowedContracts));
            hasCondition = true;
        }
        if (allowedProjects != null && !allowedProjects.isEmpty()) {
            linkWrapper.or(w -> w.eq("target_type", "PROJECT").in("target_id", allowedProjects));
            hasCondition = true;
        }
        if (allowedProposals != null && !allowedProposals.isEmpty()) {
            linkWrapper.or(w -> w.eq("target_type", "PROPOSAL").in("target_id", allowedProposals));
            hasCondition = true;
        }
        if (allowedCustomers != null && !allowedCustomers.isEmpty() && salesOrderMapper != null) {
            List<Long> allowedOrderIds = salesOrderMapper.selectOrderIdsByCustomerScope(
                    new java.util.ArrayList<>(allowedCustomers));
            if (!allowedOrderIds.isEmpty()) {
                linkWrapper.or(w -> w.eq("target_type", "SALES_ORDER").in("target_id", allowedOrderIds));
                hasCondition = true;
            }
        }

        Set<Long> allowedAcceptanceDocIds = new java.util.HashSet<>();

        // ACCEPTANCE 文書の月別複合タプルスコープ判定 (R9-P0-01)
        // 月の候補取得とdocument_id導出はMapper SQLで行う。acceptance全件をJavaへ
        // ロードしてからfilterすると、list/count/downloadの認可境界をDBで保証できない。
        if (acceptanceMapper != null) {
            List<String> documentWorkMonths = acceptanceMapper.selectDocumentWorkMonths();
            if (documentWorkMonths != null) {
                for (String month : documentWorkMonths) {
                    if (month == null || month.isBlank()) {
                        continue;
                    }
                    java.time.LocalDate monthEnd = java.time.YearMonth.parse(month).atEndOfMonth();
                    Set<Long> allowedContractsForMonth = dataScopeService.allowedContractIdsAsOf(monthEnd);
                    if (allowedContractsForMonth != null && !allowedContractsForMonth.isEmpty()) {
                        List<Long> documentIds = acceptanceMapper.selectDocumentIdsByWorkMonthAndContractIds(
                                month, new java.util.ArrayList<>(allowedContractsForMonth));
                        if (documentIds != null) {
                            allowedAcceptanceDocIds.addAll(documentIds);
                        }
                    }
                }
            }
        }

        if (!hasCondition && allowedAcceptanceDocIds.isEmpty()) {
            wrapper.eq(Document::getId, -1L);
            return;
        }

        List<DocumentLink> links = hasCondition ? documentLinkMapper.selectList(linkWrapper) : List.of();
        Set<Long> allowedDocIds = links.stream().map(DocumentLink::getDocumentId).collect(Collectors.toSet());

        if (allowedDocIds.isEmpty() && allowedAcceptanceDocIds.isEmpty()) {
            wrapper.eq(Document::getId, -1L);
        } else if (allowedAcceptanceDocIds.isEmpty()) {
            wrapper.ne(Document::getDocumentType, "ACCEPTANCE")
                    .in(Document::getId, allowedDocIds);
        } else if (allowedDocIds.isEmpty()) {
            wrapper.eq(Document::getDocumentType, "ACCEPTANCE")
                    .in(Document::getId, allowedAcceptanceDocIds);
        } else {
            // 現在DataScopeのCONTRACTリンクではACCEPTANCEを許可しない。
            // ACCEPTANCEだけは(contract_id, work_month)のas-of複合タプルで決めた集合を使う。
            wrapper.and(w -> w.and(current -> current
                            .ne(Document::getDocumentType, "ACCEPTANCE")
                            .in(Document::getId, allowedDocIds))
                    .or(acceptance -> acceptance
                            .eq(Document::getDocumentType, "ACCEPTANCE")
                            .in(Document::getId, allowedAcceptanceDocIds)));
        }
    }

    public static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256算出失敗", e);
        }
    }
}
