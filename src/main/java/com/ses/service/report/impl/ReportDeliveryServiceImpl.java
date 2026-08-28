package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.report.ReportDeliveryResult;
import com.ses.dto.report.ReportDownload;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.entity.SysUser;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.mapper.ReportRunMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.service.DocumentService;
import com.ses.service.NotificationService;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportDocumentService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * delivery状態と通知dedupeを管理する。plaintext tokenはDBへ保存せず、通知linkへ一度だけ載せる。
 */
@Service
@RequiredArgsConstructor
public class ReportDeliveryServiceImpl implements ReportDeliveryService {

    private static final String TIMEZONE = "Asia/Tokyo";
    private static final int MAX_ATTEMPTS = 5;
    private static final int LINK_DAYS = 7;
    private static final int REAUTH_MINUTES = 10;
    private final ReportRunMapper runMapper;
    private final ReportDeliveryMapper deliveryMapper;
    private final NotificationOutboxMapper notificationOutboxMapper;
    private final SysUserMapper sysUserMapper;
    private final ReportRecipientPreviewService recipientPreviewService;
    private final ReportSnapshotService snapshotService;
    private final ReportDocumentService reportDocumentService;
    private final DocumentService documentService;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDeliveryResult deliver(Long runId, String previewHash) {
        ReportRun run = runMapper.selectById(runId);
        requireReady(run);
        snapshotService.assertAccessible(run);
        ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
        if (previewHash != null && !previewHash.equals(preview.getPreviewHash())) {
            throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
        }
        ReportDocumentArtifact artifact = null;
        List<ReportDelivery> deliveries = new ArrayList<>();
        for (ReportRecipientPreview recipient : preview.getRecipients()) {
            if (!"ALLOW".equals(recipient.getScopeDecision())) continue;
            ReportDelivery delivery = find(runId, recipient.getRecipientUserId());
            // deliverは同一run/recipientの既存deliveryを再送しない。
            // ENQUEUED/PROCESSING/RETRYを新attemptへ進めるとoutbox通知が重複するため、
            // 再送はretry/manual-replayの明示操作へ限定する。
            if (delivery != null) {
                deliveries.add(delivery);
                continue;
            }
            if (artifact == null) {
                artifact = reportDocumentService.register(runId, "PDF");
            }
            deliveries.add(issue(run, delivery, recipient, artifact));
        }
        return new ReportDeliveryResult(preview, deliveries);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reauthenticate(Long deliveryId, String password) {
        ReportDelivery delivery = findRequired(deliveryId);
        Long userId = currentUserId();
        if (!userId.equals(delivery.getRecipientUserId()) || password == null || password.isBlank()) {
            throw BusinessException.of(403, "error.managementReport.reauthenticationRequired");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw BusinessException.of(403, "error.managementReport.reauthenticationFailed");
        }
        delivery.setReauthenticatedAt(now());
        deliveryMapper.updateById(delivery);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDownload download(Long deliveryId, String token, String format) {
        ReportDelivery delivery = findRequired(deliveryId);
        Long userId = currentUserId();
        if (!userId.equals(delivery.getRecipientUserId())) {
            throw BusinessException.of(403, "error.managementReport.scopeDenied");
        }
        if (token == null || delivery.getLinkTokenHash() == null
                || !sha256(token).equals(delivery.getLinkTokenHash())) {
            throw BusinessException.of(403, "error.managementReport.linkInvalid");
        }
        if (delivery.getLinkExpiresAt() == null || !delivery.getLinkExpiresAt().isAfter(now())) {
            throw BusinessException.of(403, "error.managementReport.linkExpired");
        }
        if (delivery.getReauthRequired() != null && delivery.getReauthRequired() == 1
                && (delivery.getReauthenticatedAt() == null
                || delivery.getReauthenticatedAt().isBefore(now().minusMinutes(REAUTH_MINUTES)))) {
            throw BusinessException.of(403, "error.managementReport.reauthenticationRequired");
        }
        ReportRun run = runMapper.selectById(delivery.getRunId());
        // 配布runのowner参照認可と、配布先本人のdownload認可は別物である。
        // ownerと別のmanagerでも、preview時にowner scopeを包含していた本人なら利用できる。
        // ここでは保存scopeのhashだけを検証し、owner本人であることは要求しない。
        snapshotService.scopeSnapshotOf(run);
        ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
        boolean stillAllowed = preview.getRecipients().stream().anyMatch(item ->
                userId.equals(item.getRecipientUserId()) && "ALLOW".equals(item.getScopeDecision()));
        if (!stillAllowed) {
            throw BusinessException.of(403, "error.managementReport.scopeChanged");
        }

        String normalized = format == null ? "PDF" : format.toUpperCase(java.util.Locale.ROOT);
        Long documentId = delivery.getDocumentId();
        Integer versionNo = delivery.getDocumentVersionNo();
        String fileName;
        String contentType;
        if (!"PDF".equals(normalized)) {
            ReportDocumentArtifact artifact = reportDocumentService.register(run.getId(), normalized);
            documentId = artifact.getDocument().getId();
            DocumentVersion version = artifact.getVersion();
            versionNo = version == null ? null : version.getVersionNo();
            fileName = version == null ? "management-report." + normalized.toLowerCase() : version.getOriginalName();
            contentType = contentType(normalized);
        } else {
            fileName = "management-report.pdf";
            contentType = "application/pdf";
        }
        if (documentId == null || versionNo == null
                || documentService.getVersionStorageKey(documentId, versionNo) == null) {
            throw BusinessException.of(404, "error.managementReport.documentNotFound");
        }
        delivery.setDownloadedAt(now());
        deliveryMapper.updateById(delivery);
        return new ReportDownload(documentService.download(documentId, versionNo), fileName, contentType);
    }

    @Override
    public ReportDownload preview(Long deliveryId, String token, String format) {
        // previewも文書bytesを返すため、downloadと同じtoken/expiry/reauth/scope経路を必ず通す。
        return download(deliveryId, token, format);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retry(Long deliveryId) {
        requireAdmin();
        ReportDelivery delivery = findRequired(deliveryId);
        // outbox dispatch中のdeliveryをretry APIから再送すると、同一通知のtoken/outboxが重複する。
        // 通常retryはdispatcherがRETRYへ戻したdeliveryだけを対象にし、manualReplayが明示的に
        // RETRYへ遷移させる。ENQUEUED/PROCESSING/SENT/PENDINGは状態を変えず終了する。
        if (!"RETRY".equals(delivery.getDeliveryStatus())) {
            return;
        }
        if (delivery.getAttemptCount() != null && delivery.getAttemptCount() >= MAX_ATTEMPTS) {
            delivery.setDeliveryStatus("FAILED");
            delivery.setLastErrorCode("DELIVERY_DLQ");
            delivery.setLastErrorMessage("再試行上限に達しました。手動replayが必要です。");
            deliveryMapper.updateById(delivery);
            return;
        }
        ReportRun run = runMapper.selectById(delivery.getRunId());
        ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
        ReportRecipientPreview recipient = preview.getRecipients().stream()
                .filter(item -> delivery.getRecipientUserId().equals(item.getRecipientUserId()))
                .findFirst().orElseThrow(() -> BusinessException.of(403, "error.managementReport.scopeChanged"));
        if (!"ALLOW".equals(recipient.getScopeDecision())) {
            delivery.setDeliveryStatus("FAILED");
            delivery.setLastErrorCode("RECIPIENT_SCOPE_MISMATCH");
            deliveryMapper.updateById(delivery);
            return;
        }
        // outboxのRETRYは既存行を再利用する。新しい通知を発行すると旧outboxと新outboxが
        // 同じrun/recipientを指し、二重通知または古いlinkの通知が発生する。
        if (delivery.getNotificationOutboxId() != null) {
            if (notificationOutboxMapper.requeueReport(delivery.getNotificationOutboxId()) == 0) {
                return;
            }
            delivery.setDeliveryStatus("ENQUEUED");
            delivery.setLastErrorCode(null);
            delivery.setLastErrorMessage(null);
            deliveryMapper.updateById(delivery);
            return;
        }
        // 旧データにoutbox idが無い場合だけ互換用に新規発行する。
        ReportDocumentArtifact artifact = new ReportDocumentArtifact(run.getId(), "PDF", null, null, null);
        issue(run, delivery, recipient, artifact);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualReplay(Long deliveryId) {
        requireAdmin();
        ReportDelivery delivery = findRequired(deliveryId);
        if (!"FAILED".equals(delivery.getDeliveryStatus())) {
            return;
        }
        // DLQ replayも同じnotification/outboxを再利用する。delivery attemptは監査用に保持し、
        // outboxのattemptだけをreplay世代として0へ戻すため、dedupe keyの再衝突を起こさない。
        if (delivery.getNotificationOutboxId() != null) {
            if (notificationOutboxMapper.replayReport(delivery.getNotificationOutboxId()) == 0) {
                return;
            }
            delivery.setDeliveryStatus("ENQUEUED");
            delivery.setLastErrorCode(null);
            delivery.setLastErrorMessage(null);
            deliveryMapper.updateById(delivery);
            return;
        }
        // outbox導入前のlegacy deliveryだけは既存互換の新規発行へフォールバックする。
        delivery.setAttemptCount(0);
        delivery.setDeliveryStatus("RETRY");
        delivery.setLastErrorCode(null);
        delivery.setLastErrorMessage(null);
        deliveryMapper.updateById(delivery);
        retry(deliveryId);
    }

    private ReportDelivery issue(ReportRun run, ReportDelivery existing,
                                 ReportRecipientPreview recipient, ReportDocumentArtifact artifact) {
        ReportDelivery delivery = existing == null ? new ReportDelivery() : existing;
        int attempt = delivery.getAttemptCount() == null ? 1 : delivery.getAttemptCount() + 1;
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        LocalDateTime expiresAt = now().plusDays(LINK_DAYS);
        delivery.setTenantId("default");
        delivery.setRunId(run.getId());
        if (artifact.getDocument() != null) {
            delivery.setDocumentId(artifact.getDocument().getId());
            delivery.setDocumentVersionNo(artifact.getVersion() == null ? null : artifact.getVersion().getVersionNo());
        }
        delivery.setRecipientUserId(recipient.getRecipientUserId());
        delivery.setRecipientScopeJson(toJson(recipient));
        delivery.setRecipientScopeHash(recipient.getRecipientScopeHash());
        delivery.setPreviewStatus("ALLOWED");
        delivery.setPreviewedAt(now());
        delivery.setScopeDecision("ALLOW");
        delivery.setDeliveryChannel("IN_APP_LINK");
        delivery.setDeliveryStatus("PROCESSING");
        delivery.setNotificationDedupeKey("REPORT:" + run.getId() + ":" + recipient.getRecipientUserId() + ":a" + attempt);
        delivery.setLinkTokenHash(sha256(token));
        delivery.setLinkExpiresAt(expiresAt);
        delivery.setReauthRequired(1);
        delivery.setAttemptCount(attempt);
        if (existing == null) deliveryMapper.insert(delivery);
        else deliveryMapper.updateById(delivery);
        try {
            String link = "/api/management-reports/deliveries/" + delivery.getId() + "/download?token=" + token;
            Long outboxId = notificationService.publishToUserAndGetOutboxId(
                    recipient.getRecipientUserId(), "MANAGEMENT_REPORT",
                    "月次管理レポート", "snapshotを確認できます（ダウンロード時に再認証が必要です）。",
                    link, delivery.getNotificationDedupeKey(), "management-report");
            if (outboxId == null) {
                delivery.setDeliveryStatus(attempt >= MAX_ATTEMPTS ? "FAILED" : "RETRY");
                delivery.setLastErrorCode(attempt >= MAX_ATTEMPTS ? "DELIVERY_DLQ" : "DELIVERY_OUTBOX_UNAVAILABLE");
                delivery.setLastErrorMessage("通知outboxへの登録結果を取得できませんでした");
            } else {
                delivery.setNotificationOutboxId(outboxId);
                delivery.setDeliveryStatus("ENQUEUED");
                delivery.setLastErrorCode(null);
                delivery.setLastErrorMessage(null);
            }
        } catch (Exception ex) {
            delivery.setDeliveryStatus(attempt >= MAX_ATTEMPTS ? "FAILED" : "RETRY");
            delivery.setLastErrorCode(attempt >= MAX_ATTEMPTS ? "DELIVERY_DLQ" : "DELIVERY_FAILED");
            delivery.setLastErrorMessage("通知outboxへの登録に失敗しました");
        }
        deliveryMapper.updateById(delivery);
        return delivery;
    }

    private ReportDelivery find(Long runId, Long userId) {
        return deliveryMapper.selectOne(new QueryWrapper<ReportDelivery>()
                .eq("run_id", runId).eq("recipient_user_id", userId));
    }

    private ReportDelivery findRequired(Long deliveryId) {
        ReportDelivery delivery = deliveryMapper.selectById(deliveryId);
        if (delivery == null) throw BusinessException.of(404, "error.managementReport.deliveryNotFound");
        return delivery;
    }

    private void requireReady(ReportRun run) {
        if (run == null || !"SUCCEEDED".equals(run.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.deliveryNotReady");
        }
    }

    private Long currentUserId() {
        Long id = SecurityUtils.currentUserId();
        if (id == null) throw BusinessException.of(401, "error.unauthorized");
        return id;
    }

    private void requireAdmin() {
        if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.managementReport.adminRequired");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of(TIMEZONE));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.serializationFailed");
        }
    }

    private String contentType(String format) {
        return switch (format) {
            case "XLSX" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "CSV" -> "text/csv; charset=UTF-8";
            default -> "application/pdf";
        };
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }
}
