package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.entity.NotificationOutbox;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.report.ReportDeliveryIssueService;
import com.ses.service.report.ReportDeliveryNotificationBridge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * delivery行の作成/更新とnotification outbox登録を同一トランザクションに束ねる。
 * 文書生成やstorage I/Oは呼び出し元のTX外で行う。
 * raw tokenは通知・outbox・ログへ載せず、hashのみをdeliveryへ保存する（V159 action-link）。
 */
@Service
@RequiredArgsConstructor
public class ReportDeliveryIssueServiceImpl implements ReportDeliveryIssueService {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LINK_DAYS = 7;
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private final ReportDeliveryMapper deliveryMapper;
    private final NotificationOutboxMapper notificationOutboxMapper;
    private final ReportDeliveryNotificationBridge notificationBridge;
    private final ObjectMapper objectMapper;
    private final AccountingTimezoneResolver timezoneResolver;

    private String requireTenantId() {
        return AccountingTenantContextHolder.requireTenantContext();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDelivery issue(ReportRun run, ReportDelivery existing, ReportRecipientPreview recipient,
                                ReportDocumentArtifact artifact) {
        String tenantId = requireTenantId();
        if (run == null || run.getTenantId() == null || !tenantId.equals(run.getTenantId())) {
            throw BusinessException.of(403, "error.tenant.contextMismatch");
        }
        ReportDelivery delivery = existing == null ? new ReportDelivery() : existing;
        if (existing != null && (delivery.getTenantId() == null || !tenantId.equals(delivery.getTenantId()))) {
            throw BusinessException.of(403, "error.tenant.contextMismatch");
        }
        if (artifact != null && artifact.getDocument() != null
                && (!tenantId.equals(artifact.getDocument().getTenantId())
                || artifact.getVersion() == null
                || !tenantId.equals(artifact.getVersion().getTenantId()))) {
            throw BusinessException.of(403, "error.tenant.contextMismatch");
        }
        int attempt = delivery.getAttemptCount() == null ? 1 : delivery.getAttemptCount() + 1;
        String token = randomToken();
        LocalDateTime expiresAt = now().plusDays(LINK_DAYS);
        delivery.setTenantId(tenantId);
        delivery.setRunId(run.getId());
        if (artifact != null && artifact.getDocument() != null) {
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
        // 取消後の再発行では旧attemptのoutbox参照を残さない。
        // 新規enqueue失敗時も、旧通知ではなく今回のattemptを再試行・再照合する。
        delivery.setNotificationOutboxId(null);
        delivery.setLinkTokenHash(sha256(token));
        delivery.setLinkExpiresAt(expiresAt);
        delivery.setReauthRequired(1);
        delivery.setAttemptCount(attempt);
        if (existing == null) {
            deliveryMapper.insert(delivery);
        } else {
            if (updateForTenant(delivery, tenantId) == 0) {
                throw new IllegalStateException("レポート配布状態の更新に失敗しました");
            }
        }
        // raw tokenはここで破棄し、認証済みaction URLのみを通知へ載せる。
        token = null;
        try {
            String link = "/api/management-reports/deliveries/" + delivery.getId() + "/download";
            Long outboxId = notificationBridge.publish(
                    recipient.getRecipientUserId(), "MANAGEMENT_REPORT",
                    "月次管理レポート", "snapshotを確認できます（ダウンロード時に再認証が必要です）。",
                    link, delivery.getNotificationDedupeKey(), "management-report");
            if (outboxId == null && notificationOutboxMapper != null) {
                NotificationOutbox existingOutbox =
                        notificationOutboxMapper.selectByDedupeKey(tenantId,
                                canonicalDedupeKey(delivery.getNotificationDedupeKey(), recipient.getRecipientUserId()));
                if (existingOutbox != null) {
                    if (existingOutbox.getTenantId() == null || !tenantId.equals(existingOutbox.getTenantId())) {
                        throw BusinessException.of(403, "error.tenant.contextMismatch");
                    }
                    outboxId = existingOutbox.getId();
                }
            }
            if (outboxId == null) {
                delivery.setDeliveryStatus(attempt >= MAX_ATTEMPTS ? "FAILED" : "RETRY");
                delivery.setLastErrorCode(attempt >= MAX_ATTEMPTS ? "DELIVERY_DLQ" : "DELIVERY_FAILED");
                delivery.setLastErrorMessage("通知outboxへの登録結果を取得できませんでした");
            } else {
                delivery.setNotificationOutboxId(outboxId);
                delivery.setDeliveryStatus("ENQUEUED");
                delivery.setLastErrorCode(null);
                delivery.setLastErrorMessage(null);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            delivery.setDeliveryStatus(attempt >= MAX_ATTEMPTS ? "FAILED" : "RETRY");
            delivery.setLastErrorCode(attempt >= MAX_ATTEMPTS ? "DELIVERY_DLQ" : "DELIVERY_FAILED");
            delivery.setLastErrorMessage("通知outboxへの登録に失敗しました");
        }
        if (updateForTenant(delivery, tenantId) == 0) {
            throw new IllegalStateException("レポート配布状態の更新に失敗しました");
        }
        return delivery;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markDownloaded(Long deliveryId) {
        String tenantId = requireTenantId();
        ReportDelivery delivery = deliveryMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReportDelivery>()
                .eq("tenant_id", tenantId).eq("id", deliveryId));
        if (delivery == null) {
            throw BusinessException.of(404, "error.managementReport.deliveryNotFound");
        }
        delivery.setDownloadedAt(now());
        if (updateForTenant(delivery, tenantId) == 0) {
            throw new IllegalStateException("レポート配布のダウンロード記録に失敗しました");
        }
    }

    private int updateForTenant(ReportDelivery delivery, String tenantId) {
        return deliveryMapper.update(delivery, new UpdateWrapper<ReportDelivery>()
                .eq("tenant_id", tenantId)
                .eq("id", delivery.getId()));
    }

    private LocalDateTime now() {
        return timezoneResolver.now(requireTenantId());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.serializationFailed");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String canonicalDedupeKey(String dedupeKey, Long recipientUserId) {
        if (dedupeKey == null || recipientUserId == null) {
            return dedupeKey;
        }
        String suffix = "#u" + recipientUserId;
        return dedupeKey.endsWith(suffix) ? dedupeKey : dedupeKey + suffix;
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
