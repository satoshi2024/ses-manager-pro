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
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.entity.SysUser;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.mapper.ReportRunMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.DocumentService;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportDocumentService;
import com.ses.service.report.ReportDeliveryDocumentRegistrar;
import com.ses.service.report.ReportDeliveryNotificationBridge;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * delivery状態と通知dedupeを管理する。通知リンクは認証済みsessionのaction URLとし、
 * 旧token経路を受ける場合もDBにはSHA-256 hashだけを保持する。
 */
@Service
@RequiredArgsConstructor
public class ReportDeliveryServiceImpl implements ReportDeliveryService {

    private static final String TENANT_ID = "default";
    private static final int MAX_ATTEMPTS = 5;
    private static final int LINK_DAYS = 7;
    private static final int REAUTH_MINUTES = 10;
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private final ReportRunMapper runMapper;
    private final ReportDeliveryMapper deliveryMapper;
    private final NotificationOutboxMapper notificationOutboxMapper;
    private final SysUserMapper sysUserMapper;
    private final ReportRecipientPreviewService recipientPreviewService;
    private final ReportSnapshotService snapshotService;
    private final ReportDocumentService reportDocumentService;
    private final ReportDeliveryDocumentRegistrar documentRegistrar;
    private final DocumentService documentService;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ReportDeliveryNotificationBridge notificationBridge;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final AccountingTimezoneResolver timezoneResolver;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDeliveryResult deliver(Long runId, String previewHash) {
        return AccountingTenantContextHolder.runWithTenant(TENANT_ID, tenantZone(), () -> {
            ReportRun run = runMapper.selectById(runId);
            requireReady(run);
            snapshotService.assertAccessible(run);
            ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
            if (previewHash == null || previewHash.isBlank()
                    || run.getRecipientPreviewHash() == null
                    || !previewHash.equals(run.getRecipientPreviewHash())
                    || !previewHash.equals(preview.getPreviewHash())) {
                throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
            }
            if (run.getRecipientSnapshotJson() != null
                    && !run.getRecipientSnapshotJson().equals(recipientSnapshotJson(preview))) {
                throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
            }
            ReportDocumentArtifact artifact = null;
            List<ReportDelivery> deliveries = new ArrayList<>();
            for (ReportRecipientPreview recipient : preview.getRecipients()) {
                if (!"ALLOW".equals(recipient.getScopeDecision())) continue;
                ReportDelivery delivery = find(runId, recipient.getRecipientUserId());
                if (delivery != null && !"CANCELLED".equals(delivery.getDeliveryStatus())) {
                    deliveries.add(delivery);
                    continue;
                }
                if (artifact == null) {
                    artifact = documentRegistrar.registerArtifact(runId, "PDF");
                }
                deliveries.add(issueTransactional(run, delivery, recipient, artifact));
            }
            return new ReportDeliveryResult(preview, deliveries);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    protected ReportDelivery issueTransactional(ReportRun run, ReportDelivery existing,
                                                  ReportRecipientPreview recipient,
                                                  ReportDocumentArtifact artifact) {
        return issue(run, existing, recipient, artifact);
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
        updateDeliveryChecked(delivery);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDownload download(Long deliveryId, String token, String format) {
        ReportDelivery delivery;
        if (token == null || token.isBlank()) {
            // 新しい通知はraw tokenを持たないaction URL。認証済みsessionと下記scope検証で保護する。
            delivery = deliveryMapper.selectById(deliveryId);
        } else {
            // 旧リンク互換: bearerは不可逆hashでのみ照合し、delivery:idは一致しない。
            delivery = deliveryMapper.selectByLinkTokenHash(sha256(token));
            if (delivery == null || !deliveryId.equals(delivery.getId())
                    || delivery.getLinkTokenHash() == null
                    || !delivery.getLinkTokenHash().equals(sha256(token))) {
                throw BusinessException.of(403, "error.managementReport.linkInvalid");
            }
        }
        if (delivery == null || !deliveryId.equals(delivery.getId())) {
            throw BusinessException.of(403, "error.managementReport.linkInvalid");
        }
        Long userId = currentUserId();
        if (!userId.equals(delivery.getRecipientUserId())) {
            throw BusinessException.of(403, "error.managementReport.scopeDenied");
        }
        if ("CANCELLED".equals(delivery.getDeliveryStatus())) {
            throw BusinessException.of(403, "error.managementReport.deliveryCancelled");
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
        if (run.getRecipientPreviewHash() != null
                && !run.getRecipientPreviewHash().equals(preview.getPreviewHash())) {
            throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
        }
        boolean stillAllowed = preview.getRecipients().stream().anyMatch(item ->
                userId.equals(item.getRecipientUserId()) && "ALLOW".equals(item.getScopeDecision()));
        if (!stillAllowed) {
            throw BusinessException.of(403, "error.managementReport.scopeChanged");
        }
        ReportRecipientPreview currentRecipient = preview.getRecipients().stream()
                .filter(item -> userId.equals(item.getRecipientUserId()) && "ALLOW".equals(item.getScopeDecision()))
                .findFirst().orElseThrow(() -> BusinessException.of(403, "error.managementReport.scopeChanged"));
        if (delivery.getRecipientScopeHash() == null
                || !delivery.getRecipientScopeHash().equals(currentRecipient.getRecipientScopeHash())) {
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
        updateDeliveryChecked(delivery);
        return new ReportDownload(documentService.download(documentId, versionNo), fileName, contentType);
    }

    @Override
    public ReportDownload preview(Long deliveryId, String token, String format) {
        // previewも文書bytesを返すため、downloadと同じtoken/expiry/reauth/scope経路を必ず通す。
        return download(deliveryId, token, format);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void retry(Long deliveryId) {
        requireAdmin();
        ReportDelivery delivery = findRequiredForReplay(deliveryId);
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
            updateDeliveryChecked(delivery);
            return;
        }
        ReportRun run = runMapper.selectById(delivery.getRunId());
        ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
        assertPreviewFresh(run, preview);
        ReportRecipientPreview recipient = preview.getRecipients().stream()
                .filter(item -> delivery.getRecipientUserId().equals(item.getRecipientUserId()))
                .findFirst().orElseThrow(() -> BusinessException.of(403, "error.managementReport.scopeChanged"));
        if (!"ALLOW".equals(recipient.getScopeDecision())) {
            delivery.setDeliveryStatus("FAILED");
            delivery.setLastErrorCode("RECIPIENT_SCOPE_MISMATCH");
            updateDeliveryChecked(delivery);
            return;
        }
        // outboxのRETRYは既存行を再利用する。新しい通知を発行すると旧outboxと新outboxが
        // 同じrun/recipientを指し、二重通知または古いlinkの通知が発生する。
        if (delivery.getNotificationOutboxId() != null) {
            if (notificationOutboxMapper.requeueReport(delivery.getNotificationOutboxId()) == 0) {
                reconcileReplayConflict(delivery, "DELIVERY_OUTBOX_REQUEUE_CONFLICT");
                return;
            }
            rotateDownloadToken(delivery);
            delivery.setDeliveryStatus("ENQUEUED");
            delivery.setLastErrorCode(null);
            delivery.setLastErrorMessage(null);
            updateDeliveryChecked(delivery);
            return;
        }
        // 旧データにoutbox idが無い場合も、保存済み文書を解決してから新規発行する。
        ReportDocumentArtifact artifact = resolveArtifact(run, delivery);
        issue(run, delivery, recipient, artifact);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void manualReplay(Long deliveryId) {
        requireAdmin();
        ReportDelivery delivery = findRequiredForReplay(deliveryId);
        if (!"FAILED".equals(delivery.getDeliveryStatus())) {
            return;
        }
        // DLQ replayも同じnotification/outboxを再利用する。delivery attemptは監査用に保持し、
        // outboxのattemptだけをreplay世代として0へ戻すため、dedupe keyの再衝突を起こさない。
        if (delivery.getNotificationOutboxId() != null) {
            ReportRun run = runMapper.selectById(delivery.getRunId());
            ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
            assertPreviewFresh(run, preview);
            if (notificationOutboxMapper.replayReport(delivery.getNotificationOutboxId()) == 0) {
                reconcileReplayConflict(delivery, "DELIVERY_OUTBOX_REPLAY_CONFLICT");
                return;
            }
            rotateDownloadToken(delivery);
            delivery.setDeliveryStatus("ENQUEUED");
            delivery.setLastErrorCode(null);
            delivery.setLastErrorMessage(null);
            updateDeliveryChecked(delivery);
            return;
        }
        // outbox導入前のlegacy deliveryだけは保存済み文書を解決して新規発行する。
        delivery.setAttemptCount(0);
        delivery.setDeliveryStatus("RETRY");
        delivery.setLastErrorCode(null);
        delivery.setLastErrorMessage(null);
        updateDeliveryChecked(delivery);
        ReportRun run = runMapper.selectById(delivery.getRunId());
        ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
        assertPreviewFresh(run, preview);
        ReportRecipientPreview recipient = preview.getRecipients().stream()
                .filter(item -> delivery.getRecipientUserId().equals(item.getRecipientUserId()))
                .findFirst().orElseThrow(() -> BusinessException.of(403, "error.managementReport.scopeChanged"));
        if (!"ALLOW".equals(recipient.getScopeDecision())) {
            throw BusinessException.of(403, "error.managementReport.scopeChanged");
        }
        issue(run, delivery, recipient, resolveArtifact(run, delivery));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long deliveryId) {
        requireAdmin();
        ReportDelivery delivery = findRequired(deliveryId);
        if ("CANCELLED".equals(delivery.getDeliveryStatus())) {
            return;
        }
        delivery.setDeliveryStatus("CANCELLED");
        delivery.setLinkExpiresAt(now());
        delivery.setLinkTokenHash(null);
        delivery.setLastErrorCode("DELIVERY_CANCELLED");
        delivery.setLastErrorMessage("管理者により取消されました");
        updateDeliveryChecked(delivery);
    }

    @Override
    public List<ReportDelivery> listByRun(Long runId) {
        ReportRun run = runMapper.selectById(runId);
        if (run == null) {
            throw BusinessException.of(404, "error.managementReport.runNotFound");
        }
        snapshotService.assertAccessible(run);
        return deliveryMapper.selectList(new QueryWrapper<ReportDelivery>()
                .eq("run_id", runId).orderByAsc("id"));
    }

    private ReportDelivery issue(ReportRun run, ReportDelivery existing,
                                 ReportRecipientPreview recipient, ReportDocumentArtifact artifact) {
        requireArtifact(artifact);
        ReportDelivery delivery = existing == null ? new ReportDelivery() : existing;
        int attempt = delivery.getAttemptCount() == null ? 1 : delivery.getAttemptCount() + 1;
        // raw tokenはhashだけをdeliveryへ保存し、通知には認証済みaction URLだけを載せる。
        String token = randomToken();
        LocalDateTime expiresAt = now().plusDays(LINK_DAYS);
        delivery.setTenantId("default");
        delivery.setRunId(run.getId());
        delivery.setDocumentId(artifact.getDocument().getId());
        delivery.setDocumentVersionNo(artifact.getVersion().getVersionNo());
        delivery.setRecipientUserId(recipient.getRecipientUserId());
        delivery.setRecipientScopeJson(toJson(recipient));
        delivery.setRecipientScopeHash(recipient.getRecipientScopeHash());
        delivery.setPreviewStatus("ALLOWED");
        delivery.setPreviewedAt(now());
        delivery.setScopeDecision("ALLOW");
        delivery.setDeliveryChannel("IN_APP_LINK");
        delivery.setDeliveryStatus("PROCESSING");
        delivery.setNotificationDedupeKey("REPORT:" + run.getId() + ":" + recipient.getRecipientUserId()
                + ":a" + attempt + "#u" + recipient.getRecipientUserId());
        delivery.setLinkTokenHash(sha256(token));
        delivery.setLinkExpiresAt(expiresAt);
        delivery.setReauthRequired(1);
        delivery.setAttemptCount(attempt);
        if (existing == null) deliveryMapper.insert(delivery);
        else updateDeliveryChecked(delivery);
        try {
            String link = "/api/management-reports/deliveries/" + delivery.getId() + "/download";
            Long outboxId = notificationBridge.publish(
                    recipient.getRecipientUserId(), "MANAGEMENT_REPORT",
                    "月次管理レポート", "snapshotを確認できます（ダウンロード時に再認証が必要です）。",
                    link, delivery.getNotificationDedupeKey(), "management-report");
            if (outboxId == null) {
                com.ses.entity.NotificationOutbox existingOutbox =
                        notificationOutboxMapper.selectByDedupeKey(delivery.getNotificationDedupeKey());
                if (existingOutbox != null) {
                    outboxId = existingOutbox.getId();
                }
            }
            if (outboxId == null) {
                delivery.setDeliveryStatus(attempt >= MAX_ATTEMPTS ? "FAILED" : "RETRY");
                // outbox登録の例外はbridgeが結果式へ変換するため、再試行可能な配布失敗として保持する。
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
        updateDeliveryChecked(delivery);
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

    private ReportDelivery findRequiredForReplay(Long deliveryId) {
        ReportDelivery delivery = deliveryMapper.selectByIdForReplay(deliveryId);
        if (delivery == null) throw BusinessException.of(404, "error.managementReport.deliveryNotFound");
        return delivery;
    }

    private ReportDocumentArtifact resolveArtifact(ReportRun run, ReportDelivery delivery) {
        if (delivery.getDocumentId() != null && delivery.getDocumentVersionNo() != null) {
            Document document = documentMapper.selectById(delivery.getDocumentId());
            DocumentVersion version = documentVersionMapper.selectOne(new QueryWrapper<DocumentVersion>()
                    .eq("document_id", delivery.getDocumentId())
                    .eq("version_no", delivery.getDocumentVersionNo()));
            if (document != null && version != null
                    && documentService.getVersionStorageKey(document.getId(), version.getVersionNo()) != null) {
                return new ReportDocumentArtifact(run.getId(), "PDF", version.getSha256(), document, version);
            }
        }
        return documentRegistrar.registerArtifact(run.getId(), "PDF");
    }

    private void requireArtifact(ReportDocumentArtifact artifact) {
        if (artifact == null || artifact.getDocument() == null || artifact.getDocument().getId() == null
                || artifact.getVersion() == null || artifact.getVersion().getVersionNo() == null
                || documentService.getVersionStorageKey(artifact.getDocument().getId(),
                artifact.getVersion().getVersionNo()) == null) {
            throw BusinessException.of(500, "error.managementReport.documentNotFound");
        }
    }

    private void rotateDownloadToken(ReportDelivery delivery) {
        delivery.setLinkTokenHash(sha256(randomToken()));
        delivery.setLinkExpiresAt(now().plusDays(LINK_DAYS));
        delivery.setReauthRequired(1);
        delivery.setReauthenticatedAt(null);
    }

    private void reconcileReplayConflict(ReportDelivery delivery, String errorCode) {
        delivery.setLastErrorCode(errorCode);
        delivery.setLastErrorMessage("通知outboxの状態競合を検出したため再照合が必要です");
        updateDeliveryChecked(delivery);
    }

    private void updateDeliveryChecked(ReportDelivery delivery) {
        int updated = deliveryMapper.updateById(delivery);
        if (updated > 0) {
            return;
        }
        ReportDelivery current = delivery.getId() == null ? null : deliveryMapper.selectById(delivery.getId());
        boolean terminal = "SENT".equals(delivery.getDeliveryStatus())
                || "FAILED".equals(delivery.getDeliveryStatus())
                || "CANCELLED".equals(delivery.getDeliveryStatus());
        if (terminal && current != null && java.util.Objects.equals(current.getDeliveryStatus(), delivery.getDeliveryStatus())
                && java.util.Objects.equals(current.getNotificationOutboxId(), delivery.getNotificationOutboxId())) {
            return;
        }
        throw new IllegalStateException("レポート配布状態の更新に失敗しました。再照合が必要です (deliveryId="
                + delivery.getId() + ")");
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
        return timezoneResolver.now(TENANT_ID);
    }

    private ZoneId tenantZone() {
        return timezoneResolver.resolve(TENANT_ID);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.serializationFailed");
        }
    }

    private String recipientSnapshotJson(ReportRecipientPreviewResult preview) {
        try {
            List<ReportRecipientPreview> sorted = preview.getRecipients() == null ? List.of()
                    : preview.getRecipients().stream()
                    .sorted(java.util.Comparator.comparing(ReportRecipientPreview::getRecipientUserId,
                            java.util.Comparator.nullsLast(Long::compareTo))
                            .thenComparing(ReportRecipientPreview::getRecipientRole,
                                    java.util.Comparator.nullsLast(String::compareTo))
                            .thenComparing(ReportRecipientPreview::getScopeDecision,
                                    java.util.Comparator.nullsLast(String::compareTo)))
                    .toList();
            return objectMapper.writeValueAsString(sorted);
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.serializationFailed");
        }
    }

    private void assertPreviewFresh(ReportRun run, ReportRecipientPreviewResult preview) {
        if (run == null || run.getRecipientPreviewHash() == null || run.getRecipientPreviewHash().isBlank()
                || preview == null || !run.getRecipientPreviewHash().equals(preview.getPreviewHash())
                || run.getRecipientSnapshotJson() == null
                || !run.getRecipientSnapshotJson().equals(recipientSnapshotJson(preview))) {
            throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
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
