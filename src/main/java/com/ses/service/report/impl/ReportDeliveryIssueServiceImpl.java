package com.ses.service.report.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.entity.NotificationOutbox;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.report.ReportDeliveryIssueService;
import com.ses.service.report.ReportDeliveryNotificationBridge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * delivery行の作成/更新とnotification outbox登録を同一トランザクションに束ねる。
 * 文書生成やstorage I/Oは呼び出し元のTX外で行う。
 */
@Service
@RequiredArgsConstructor
public class ReportDeliveryIssueServiceImpl implements ReportDeliveryIssueService {

    private static final String TENANT_ID = "default";
    private static final int MAX_ATTEMPTS = 5;
    private static final int LINK_DAYS = 7;

    private final ReportDeliveryMapper deliveryMapper;
    private final NotificationOutboxMapper notificationOutboxMapper;
    private final ReportDeliveryNotificationBridge notificationBridge;
    private final ObjectMapper objectMapper;
    private final AccountingTimezoneResolver timezoneResolver;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDelivery issue(ReportRun run, ReportDelivery existing, ReportRecipientPreview recipient,
                                ReportDocumentArtifact artifact) {
        ReportDelivery delivery = existing == null ? new ReportDelivery() : existing;
        int attempt = delivery.getAttemptCount() == null ? 1 : delivery.getAttemptCount() + 1;
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        LocalDateTime expiresAt = now().plusDays(LINK_DAYS);
        delivery.setTenantId(TENANT_ID);
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
        delivery.setLinkTokenHash(sha256(token));
        delivery.setLinkExpiresAt(expiresAt);
        delivery.setReauthRequired(1);
        delivery.setAttemptCount(attempt);
        if (existing == null) {
            deliveryMapper.insert(delivery);
        } else {
            deliveryMapper.updateById(delivery);
        }
        try {
            String link = "/api/management-reports/deliveries/" + delivery.getId() + "/download?token=" + token;
            Long outboxId = notificationBridge.publish(
                    recipient.getRecipientUserId(), "MANAGEMENT_REPORT",
                    "月次管理レポート", "snapshotを確認できます（ダウンロード時に再認証が必要です）。",
                    link, delivery.getNotificationDedupeKey(), "management-report");
            if (outboxId == null && notificationOutboxMapper != null) {
                NotificationOutbox existingOutbox =
                        notificationOutboxMapper.selectByDedupeKey(delivery.getNotificationDedupeKey());
                if (existingOutbox != null) {
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
        } catch (Exception ex) {
            delivery.setDeliveryStatus(attempt >= MAX_ATTEMPTS ? "FAILED" : "RETRY");
            delivery.setLastErrorCode(attempt >= MAX_ATTEMPTS ? "DELIVERY_DLQ" : "DELIVERY_FAILED");
            delivery.setLastErrorMessage("通知outboxへの登録に失敗しました");
        }
        deliveryMapper.updateById(delivery);
        return delivery;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markDownloaded(Long deliveryId) {
        ReportDelivery delivery = deliveryMapper.selectById(deliveryId);
        if (delivery == null) {
            throw BusinessException.of(404, "error.managementReport.deliveryNotFound");
        }
        delivery.setDownloadedAt(now());
        deliveryMapper.updateById(delivery);
    }

    private LocalDateTime now() {
        return timezoneResolver.now(TENANT_ID);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.serializationFailed");
        }
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
