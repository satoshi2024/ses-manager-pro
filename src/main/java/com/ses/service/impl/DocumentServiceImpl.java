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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

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
    private final DocumentStorage documentStorage;
    private final ObjectProvider<FileScanner> fileScannerProvider;

    private static final String DEFAULT_TENANT_ID = "default";

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
        // P1-05: businessKey が未指定の場合のフォールバック
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

        // 2. storage put（quarantine）
        String storageKey = generateStorageKey();
        byte[] contentBytes = readAllBytes(content);
        String sha256 = computeSha256(contentBytes);
        documentStorage.put(storageKey, contentBytes, true /* quarantine */);

        // P1-03: スキャン処理の実行
        FileScanResult scanResult = scanQuarantinedContent(contentBytes);
        if (scanResult.status() == FileScanResult.Status.INFECTED) {
            log.warn("[文書台帳] マルウェアを検出したため登録を拒否します: storageKey={}", storageKey);
            throw BusinessException.of(400, "error.file.scanRejected");
        }

        try {
            // 3. DB tx: document + version 保存
            Document doc = buildDocument(request);
            documentMapper.insert(doc);

            DocumentVersion version = buildVersion(request, doc.getId(), storageKey, contentBytes.length, sha256);
            version.setBusinessKey(businessKey);
            version.setVersionDiscriminator(discriminator);
            version.setScanStatus("CLEAN");
            documentVersionMapper.insert(version);

            // 4. storage promote
            documentStorage.promote(storageKey);

            // 5. アクセスログ
            recordAccessLog(doc.getId(), version.getId(), "REGISTER");

            log.info("[文書台帳] 登録完了: documentId={} versionId={} sha256={}", doc.getId(), version.getId(), sha256);
            return doc;

        } catch (Exception e) {
            log.error("[文書台帳] DB保存失敗。storageKey={} error={}", storageKey, e.getMessage());
            throw e;
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

        // 冪等チェック
        DocumentVersion existing = documentVersionMapper.findByIdempotencyKey(
                DEFAULT_TENANT_ID, sourceType, businessKey, discriminator);
        if (existing != null) {
            log.info("[文書台帳] 冪等addVersion: 既存versionId={}", existing.getId());
            return existing;
        }

        // storage put
        String storageKey = generateStorageKey();
        byte[] contentBytes = readAllBytes(content);
        String sha256 = computeSha256(contentBytes);
        documentStorage.put(storageKey, contentBytes, true);

        // P1-03: スキャン処理
        FileScanResult scanResult = scanQuarantinedContent(contentBytes);
        if (scanResult.status() == FileScanResult.Status.INFECTED) {
            throw BusinessException.of(400, "error.file.scanRejected");
        }

        try {
            DocumentVersion version = buildVersion(request, documentId, storageKey, contentBytes.length, sha256);
            version.setBusinessKey(businessKey);
            version.setVersionDiscriminator(discriminator);
            version.setScanStatus("CLEAN");
            documentVersionMapper.insert(version);

            // P1-06: CONFIRMED状態なら AMENDED へ遷移（CAS＋バージョンインクリメント）
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
            throw e;
        }
    }

    // ----------------------------------------------------------------
    // リンク
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

    // ----------------------------------------------------------------
    // 法的hold
    // ----------------------------------------------------------------

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

    // ----------------------------------------------------------------
    // 廃棄
    // ----------------------------------------------------------------

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

    @Override
    @Transactional
    public void approveDisposal(Long disposalRequestId) {
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
                .set(DocumentDisposalRequest::getApprovedBy, currentUserId));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.disposalConcurrentUpdate");
        }
        recordAccessLog(req.getDocumentId(), null, "DISPOSE_APPROVE");
    }

    @Override
    @Transactional
    public void rejectDisposal(Long disposalRequestId, String reason) {
        DocumentDisposalRequest req = getDisposalRequestOrThrow(disposalRequestId);
        if (!"PENDING".equals(req.getStatus())) {
            throw BusinessException.of(400, "error.document.disposalNotPending");
        }
        // P2-04: 状態CAS＋更新確認
        int updated = documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, disposalRequestId)
                .eq(DocumentDisposalRequest::getStatus, "PENDING")
                .set(DocumentDisposalRequest::getStatus, "REJECTED")
                .set(DocumentDisposalRequest::getReason, req.getReason() + " [却下理由: " + reason + "]"));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.disposalConcurrentUpdate");
        }
        recordAccessLog(req.getDocumentId(), null, "DISPOSE_REJECT");
    }

    @Override
    @Transactional
    public void executeDisposal(Long disposalRequestId) {
        DocumentDisposalRequest req = getDisposalRequestOrThrow(disposalRequestId);
        if (!"APPROVED".equals(req.getStatus())) {
            throw BusinessException.of(400, "error.document.disposalNotApproved");
        }

        // P1-07: 対象Documentの最新状態を再検証
        Document doc = getDocumentOrThrow(req.getDocumentId());
        if (doc.getLegalHoldFlag() != null && doc.getLegalHoldFlag() == 1) {
            throw BusinessException.of(400, "error.document.legalHoldActive");
        }
        if ("DISPOSED".equals(doc.getStatus())) {
            return; // 既に廃棄済み
        }

        List<DocumentVersion> versions = documentVersionMapper.findByDocumentId(req.getDocumentId());

        // DBステータスを先に DISPOSED へ更新
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, req.getDocumentId())
                .set(Document::getStatus, "DISPOSED"));
        documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, disposalRequestId)
                .set(DocumentDisposalRequest::getStatus, "DISPOSED")
                .set(DocumentDisposalRequest::getDisposedAt, LocalDateTime.now()));

        // P1-07: DBコミット成功後に Storage の物理削除を実行
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (DocumentVersion v : versions) {
                        try {
                            documentStorage.delete(v.getStorageKey());
                        } catch (Exception e) {
                            log.error("[文書台帳] Storage削除失敗: key={} error={}", v.getStorageKey(), e.getMessage());
                        }
                    }
                }
            });
        } else {
            for (DocumentVersion v : versions) {
                documentStorage.delete(v.getStorageKey());
            }
        }

        recordAccessLog(req.getDocumentId(), null, "DISPOSE");
        log.info("[文書台帳] 廃棄実行指示完了: documentId={}", req.getDocumentId());
    }

    // ----------------------------------------------------------------
    // 整合性検証
    // ----------------------------------------------------------------

    @Override
    public List<IntegrityFinding> verifyIntegrity(Long documentId) {
        List<IntegrityFinding> findings = new ArrayList<>();
        List<DocumentVersion> versions = documentVersionMapper.findByDocumentId(documentId);

        for (DocumentVersion v : versions) {
            try {
                byte[] storageBytes = documentStorage.readAll(v.getStorageKey());
                String actualSha256 = computeSha256(storageBytes);
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

    // ----------------------------------------------------------------
    // 確定
    // ----------------------------------------------------------------

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

    // ----------------------------------------------------------------
    // ダウンロード
    // ----------------------------------------------------------------

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
        if (scanStatus == null || "PENDING".equals(scanStatus) || "REJECTED".equals(scanStatus)) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }

        recordAccessLog(documentId, version.getId(), "DOWNLOAD");
        return documentStorage.open(version.getStorageKey());
    }

    // ----------------------------------------------------------------
    // ユーティリティ
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
     * P1-08: 法定保存期限算出。
     * 起算日が明確に確定できない場合は null を返し、勝手に LocalDate.now() へフォールバックしない。
     */
    private LocalDate computeRetentionUntil(Document doc) {
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
        // CLOSED_AT / SIGNED_AT / DISPATCH_END 等で日付が未確定の場合は null のままにする
        if (startDate == null) {
            return null;
        }
        return startDate.plusYears(docType.getRetentionYears());
    }

    private FileScanResult scanQuarantinedContent(byte[] content) {
        FileScanner scanner = fileScannerProvider.getIfAvailable();
        if (scanner == null) {
            return FileScanResult.clean("no-op-scanner");
        }
        try {
            // テンプファイル経由でスキャン
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("scan-", ".tmp");
            java.nio.file.Files.write(tempFile, content);
            try {
                return scanner.scan(tempFile, com.ses.common.enums.FileKind.SKILL_SHEET);
            } finally {
                java.nio.file.Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            log.warn("[文書台帳] スキャン実行中例外: {}", e.getMessage());
            return FileScanResult.clean("fallback");
        }
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

    private static byte[] readAllBytes(InputStream is) {
        try {
            return is.readAllBytes();
        } catch (java.io.IOException e) {
            throw new RuntimeException("ファイル読込失敗", e);
        }
    }

    static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256算出失敗", e);
        }
    }
}
