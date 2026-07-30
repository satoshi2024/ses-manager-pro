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
import com.ses.service.storage.DocumentStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *
 * <h3>保存フロー（design §2）</h3>
 * <ol>
 *   <li>quarantine put（storage側）</li>
 *   <li>SHA-256 hash計算</li>
 *   <li>DB tx: metadata保存（t_document, t_document_version）</li>
 *   <li>promote（storage側）</li>
 * </ol>
 * DB commitが失敗した場合、storage側はorphanとして残す。
 * 補償削除は {@code cleanup-safety-hours} 経過後のスケジューラーが行う（即削除しない）。
 *
 * <h3>冪等制御（design §6.3）</h3>
 * {@code (source_type, business_key, version_discriminator)} のDB UNIQUE制約で
 * 同一sourceからの再登録を防ぐ。UNIQUE違反は既存のDocumentVersionを返す。
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

    /**
     * 共通登録フロー。
     * 冪等キーで既存版を検索し、存在する場合はその文書を返す（2件目を作らない）。
     */
    private Document doRegister(DocumentRegisterRequest request, InputStream content) {
        // 1. 冪等チェック: 既存版が存在する場合は親文書を返す
        DocumentVersion existingVersion = documentVersionMapper.findByIdempotencyKey(
                request.getSourceType(), request.getBusinessKey(), request.getVersionDiscriminator());
        if (existingVersion != null) {
            log.info("[文書台帳] 冪等登録: source_type={} business_key={} version_discriminator={} → 既存documentId={}",
                    request.getSourceType(), request.getBusinessKey(),
                    request.getVersionDiscriminator(), existingVersion.getDocumentId());
            return documentMapper.selectById(existingVersion.getDocumentId());
        }

        // 2. storage put（quarantine）
        String storageKey = generateStorageKey();
        byte[] contentBytes = readAllBytes(content);
        String sha256 = computeSha256(contentBytes);
        documentStorage.put(storageKey, contentBytes, true /* quarantine */);

        try {
            // 3. DB tx: document + version 保存
            Document doc = buildDocument(request);
            documentMapper.insert(doc);

            DocumentVersion version = buildVersion(request, doc.getId(), storageKey, contentBytes.length, sha256);
            documentVersionMapper.insert(version);

            // 4. storage promote
            documentStorage.promote(storageKey);

            // 5. アクセスログ
            recordAccessLog(doc.getId(), version.getId(), "REGISTER");

            log.info("[文書台帳] 登録完了: documentId={} versionId={} sha256={}", doc.getId(), version.getId(), sha256);
            return doc;

        } catch (Exception e) {
            // DB失敗時: storage orphanを残す（即削除しない）
            log.error("[文書台帳] DB保存失敗。storageKey={} はcleanupSchedulerへ委ねる。error={}", storageKey, e.getMessage());
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

        // 冪等チェック
        DocumentVersion existing = documentVersionMapper.findByIdempotencyKey(
                request.getSourceType(), request.getBusinessKey(), request.getVersionDiscriminator());
        if (existing != null) {
            log.info("[文書台帳] 冪等addVersion: 既存versionId={}", existing.getId());
            return existing;
        }

        // storage put
        String storageKey = generateStorageKey();
        byte[] contentBytes = readAllBytes(content);
        String sha256 = computeSha256(contentBytes);
        documentStorage.put(storageKey, contentBytes, true);

        try {
            DocumentVersion version = buildVersion(request, documentId, storageKey, contentBytes.length, sha256);
            documentVersionMapper.insert(version);

            // CONFIRMED状態なら AMENDED へ遷移
            if ("CONFIRMED".equals(doc.getStatus())) {
                documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                        .eq(Document::getId, documentId)
                        .eq(Document::getVersion, doc.getVersion())
                        .set(Document::getStatus, "AMENDED"));
            }

            documentStorage.promote(storageKey);
            recordAccessLog(documentId, version.getId(), "AMEND");

            log.info("[文書台帳] 版追加完了: documentId={} newVersionId={}", documentId, version.getId());
            return version;

        } catch (Exception e) {
            log.error("[文書台帳] 版追加DB失敗。storageKey={} はcleanupSchedulerへ委ねる。error={}", storageKey, e.getMessage());
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
        // UNIQUE制約で重複を防ぐ（既存の場合は無視）
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
            return; // 変更なし
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

        // legal hold guard（R4.2）
        if (doc.getLegalHoldFlag() != null && doc.getLegalHoldFlag() == 1) {
            throw BusinessException.of(400, "error.document.legalHoldActive");
        }
        // retention_until IS NULL guard（design §6.1）
        if (doc.getRetentionUntil() == null) {
            throw BusinessException.of(400, "error.document.retentionUndetermined");
        }
        // CONFIRMED/AMENDED/CANCELLEDのみ廃棄申請可
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
        // 申請者と承認者は同一不可（R4.3: 単独管理者の即時物理削除禁止）
        if (currentUserId.equals(req.getRequestedBy())) {
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
        documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, disposalRequestId)
                .set(DocumentDisposalRequest::getStatus, "REJECTED")
                .set(DocumentDisposalRequest::getReason, req.getReason() + " [却下理由: " + reason + "]"));
        recordAccessLog(req.getDocumentId(), null, "DISPOSE_REJECT");
    }

    @Override
    @Transactional
    public void executeDisposal(Long disposalRequestId) {
        DocumentDisposalRequest req = getDisposalRequestOrThrow(disposalRequestId);
        if (!"APPROVED".equals(req.getStatus())) {
            throw BusinessException.of(400, "error.document.disposalNotApproved");
        }

        // 全版のstorage削除（外部APIはtransaction外が理想だが、廃棄証跡との原子性を優先して同一txで実施）
        List<DocumentVersion> versions = documentVersionMapper.findByDocumentId(req.getDocumentId());
        List<String> failedKeys = new ArrayList<>();
        for (DocumentVersion v : versions) {
            try {
                documentStorage.delete(v.getStorageKey());
            } catch (Exception e) {
                log.error("[文書台帳] storage削除失敗: key={} error={}", v.getStorageKey(), e.getMessage());
                failedKeys.add(v.getStorageKey());
            }
        }

        if (!failedKeys.isEmpty()) {
            // storage失敗 → FAILEDで廃棄証跡を記録（R4.3）
            documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                    .eq(DocumentDisposalRequest::getId, disposalRequestId)
                    .set(DocumentDisposalRequest::getStatus, "FAILED")
                    .set(DocumentDisposalRequest::getReason,
                            req.getReason() + " [storage削除失敗: " + String.join(",", failedKeys) + "]"));
            throw BusinessException.of(500, "error.document.disposalStorageFailed");
        }

        // DB: 文書ステータスをDISPOSEDへ、廃棄申請を完了へ
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, req.getDocumentId())
                .set(Document::getStatus, "DISPOSED"));
        documentDisposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, disposalRequestId)
                .set(DocumentDisposalRequest::getStatus, "DISPOSED")
                .set(DocumentDisposalRequest::getDisposedAt, LocalDateTime.now()));

        recordAccessLog(req.getDocumentId(), null, "DISPOSE");
        log.info("[文書台帳] 廃棄完了: documentId={}", req.getDocumentId());
    }

    // ----------------------------------------------------------------
    // 整合性検証（read-only）
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
            } catch (java.io.IOException e) {
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
        // findingsが空でも自動修復・自動削除はしない（design §6.3）
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

        // retention_until の算出・固定
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
        // scan未完了はfail-closed（design §6.1: NULL/PENDINGは閲覧不可）
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
        doc.setTenantId("default");
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
        // 次のversion_no = 現在の最大 + 1
        DocumentVersion latest = documentVersionMapper.findLatestByDocumentId(documentId);
        int nextVersionNo = (latest == null) ? 1 : latest.getVersionNo() + 1;

        DocumentVersion v = new DocumentVersion();
        v.setDocumentId(documentId);
        v.setVersionNo(nextVersionNo);
        v.setStorageKey(storageKey);
        v.setOriginalName(request.getOriginalName() != null ? request.getOriginalName() : "document");
        v.setContentType(request.getContentType());
        v.setSizeBytes(sizeBytes);
        v.setSha256(sha256);
        v.setSourceType(request.getSourceType());
        v.setBusinessKey(request.getBusinessKey());
        v.setVersionDiscriminator(request.getVersionDiscriminator());
        v.setExternalId(request.getExternalId());
        v.setScanStatus("CLEAN"); // デフォルトCLEAN（F2でscanフロー実装後にPENDINGへ変更）
        v.setChangeReason(request.getChangeReason());
        // createdByはAutoFillで設定
        return v;
    }

    /**
     * retention_untilを算出する。
     * m_document_typeのretention_start_ruleとretention_yearsから計算する。
     */
    private LocalDate computeRetentionUntil(Document doc) {
        DocumentType docType = documentTypeMapper.selectOne(
                new LambdaQueryWrapper<DocumentType>().eq(DocumentType::getCode, doc.getDocumentType()));
        if (docType == null || docType.getRetentionYears() == null) {
            return null; // 種別不明は未確定のまま
        }

        LocalDate startDate;
        String rule = docType.getRetentionStartRule();
        if (rule == null) {
            startDate = LocalDate.now();
        } else {
            startDate = switch (rule) {
                case "TRANSACTION_DATE" -> doc.getTransactionDate() != null ? doc.getTransactionDate() : LocalDate.now();
                case "SIGNED_AT"        -> LocalDate.now(); // 署名日はF2で設定
                case "CLOSED_AT"        -> LocalDate.now(); // 契約終了日はB1でリンク設定
                case "DISPATCH_END"     -> LocalDate.now(); // 派遣終了日はB1でリンク設定
                default                 -> LocalDate.now();
            };
        }
        return startDate.plusYears(docType.getRetentionYears());
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
            // アクセスログ失敗はビジネス処理を止めない
            log.warn("[文書台帳] アクセスログ記録失敗: documentId={} action={} error={}",
                    documentId, action, e.getMessage());
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
