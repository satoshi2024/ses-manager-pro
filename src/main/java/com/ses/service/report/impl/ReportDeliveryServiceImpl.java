package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.report.ReportDeliveryResult;
import com.ses.dto.report.ReportDownload;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.dto.report.ReportScheduledDeliveryContext;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.NotificationOutbox;
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
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportDocumentService;
import com.ses.service.report.ReportDeliveryDocumentRegistrar;
import com.ses.service.report.ReportDeliveryIssueService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.accounting.AccountingTimezoneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * delivery状態と通知dedupeを管理する。
 * raw tokenはDB/通知/outboxへ保存せずhashのみ保持し、通知は認証済みaction URLを使う。
 * 文書生成・storage read/writeは短いDB TXの外で実行する。
 */
@Service
@RequiredArgsConstructor
public class ReportDeliveryServiceImpl implements ReportDeliveryService {

    private static final int MAX_ATTEMPTS = 5;
    private static final int REAUTH_MINUTES = 10;
    private static final int LINK_DAYS = 7;
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private final ReportRunMapper runMapper;
    private final ReportDeliveryMapper deliveryMapper;
    private final NotificationOutboxMapper notificationOutboxMapper;
    private final SysUserMapper sysUserMapper;
    private final ReportRecipientPreviewService recipientPreviewService;
    private final ReportSnapshotService snapshotService;
    private final ReportDocumentService reportDocumentService;
    private final ReportDeliveryDocumentRegistrar documentRegistrar;
    private final ReportDeliveryIssueService deliveryIssueService;
    private final DocumentService documentService;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final AccountingTimezoneResolver timezoneResolver;

    @Override
    public ReportDeliveryResult deliverUser(Long runId, String requiredPreviewHash) {
        requirePreviewHash(requiredPreviewHash);
        String tenantId = requireTenant();
        ReportRun run = findRun(runId, tenantId);
        requireReady(run);
        snapshotService.assertAccessible(run);
        ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
        assertPreviewHashMatches(requiredPreviewHash, preview.getPreviewHash());
        if (run.getRecipientPreviewHash() != null && !run.getRecipientPreviewHash().equals(preview.getPreviewHash())) {
            throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
        }
        if (run.getRecipientSnapshotJson() != null && !run.getRecipientSnapshotJson().equals(recipientSnapshotJson(preview))) {
            throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
        }
        return deliverRecipients(run, preview);
    }

    @Override
    public ReportDeliveryResult deliver(Long runId, String previewHash) {
        return deliverUser(runId, previewHash);
    }

    @Override
    public ReportDeliveryResult deliverScheduled(Long runId, ReportScheduledDeliveryContext systemContext) {
        if (systemContext == null) {
            throw BusinessException.of(400, "error.managementReport.systemContextRequired");
        }
        String tenantId = requireTenant();
        ReportRun run = findRun(runId, tenantId);
        requireReady(run);
        assertScheduledContext(run, systemContext);
        snapshotService.assertAccessible(run);
        ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
        return deliverRecipients(run, preview);
    }

    private ReportDeliveryResult deliverRecipients(ReportRun run, ReportRecipientPreviewResult preview) {
        String tenantId = requireTenant();
        ReportDocumentArtifact artifact = null;
        List<ReportDelivery> deliveries = new ArrayList<>();
        for (ReportRecipientPreview recipient : preview.getRecipients()) {
            if (!"ALLOW".equals(recipient.getScopeDecision())) {
                continue;
            }
            ReportDelivery delivery = find(run.getId(), recipient.getRecipientUserId(), tenantId);
            if (delivery != null && !"CANCELLED".equals(delivery.getDeliveryStatus())) {
                deliveries.add(delivery);
                continue;
            }
            if (delivery != null) {
                ensurePreviousOutboxSettled(delivery, tenantId);
            }
            if (artifact == null) {
                artifact = documentRegistrar.registerArtifact(run.getId(), "PDF");
            }
            deliveries.add(deliveryIssueService.issue(run, delivery, recipient, artifact));
        }
        return new ReportDeliveryResult(preview, deliveries);
    }

    private void ensurePreviousOutboxSettled(ReportDelivery delivery, String tenantId) {
        Long previousOutboxId = delivery.getNotificationOutboxId();
        if (previousOutboxId == null) {
            return;
        }
        NotificationOutbox outbox = notificationOutboxMapper.selectByIdForDispatch(tenantId, previousOutboxId);
        if (outbox == null) {
            delivery.setLastErrorCode("DELIVERY_OUTBOX_REFERENCE_MISSING");
            delivery.setLastErrorMessage("旧通知outboxを確認できないため再照合が必要です");
            updateDeliveryChecked(delivery);
            throw BusinessException.of(409, "error.managementReport.deliveryReconciliationPending");
        }
        if (outbox.getTenantId() == null || !tenantId.equals(outbox.getTenantId())) {
            throw BusinessException.of(403, "error.tenant.contextMismatch");
        }
        if ("PENDING".equals(outbox.getStatus()) || "RETRY".equals(outbox.getStatus())) {
            if (notificationOutboxMapper.cancelPendingReport(tenantId, previousOutboxId) > 0) {
                return;
            }
            outbox = notificationOutboxMapper.selectByIdForDispatch(tenantId, previousOutboxId);
        }
        if (outbox != null && ("PROCESSING".equals(outbox.getStatus())
                || Integer.valueOf(1).equals(outbox.getReconciliationRequired())
                || "PENDING".equals(outbox.getStatus()) || "RETRY".equals(outbox.getStatus()))) {
            throw BusinessException.of(409, "error.managementReport.deliveryReconciliationPending");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reauthenticate(Long deliveryId, String password) {
        String tenantId = requireTenant();
        ReportDelivery delivery = findRequired(deliveryId, tenantId);
        Long userId = currentUserId();
        if (!userId.equals(delivery.getRecipientUserId()) || password == null || password.isBlank()) {
            throw BusinessException.of(403, "error.managementReport.reauthenticationRequired");
        }
        SysUser user = sysUserMapper.selectByIdAndTenant(userId, tenantId);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw BusinessException.of(403, "error.managementReport.reauthenticationFailed");
        }
        delivery.setReauthenticatedAt(now());
        updateDeliveryChecked(delivery);
    }

    @Override
    public ReportDownload download(Long deliveryId, String token, String format) {
        ResolvedDownload resolved = resolveDownload(deliveryId, token, format);
        deliveryIssueService.markDownloaded(deliveryId);
        return new ReportDownload(
                documentService.download(resolved.documentId, resolved.versionNo),
                resolved.fileName,
                resolved.contentType);
    }

    @Override
    public ReportDownload preview(Long deliveryId, String token, String format) {
        return download(deliveryId, token, format);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void retry(Long deliveryId) {
        requireAdmin();
        String tenantId = requireTenant();
        ReportDelivery delivery = findRequiredForReplay(deliveryId, tenantId);
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
        ReportRun run = findRun(delivery.getRunId(), tenantId);
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
        if (delivery.getNotificationOutboxId() != null) {
            if (notificationOutboxMapper.requeueReport(tenantId, delivery.getNotificationOutboxId()) == 0) {
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
        ReportDocumentArtifact artifact = resolveArtifact(run, delivery);
        deliveryIssueService.issue(run, delivery, recipient, artifact);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void manualReplay(Long deliveryId) {
        requireAdmin();
        String tenantId = requireTenant();
        ReportDelivery delivery = findRequiredForReplay(deliveryId, tenantId);
        if (!"FAILED".equals(delivery.getDeliveryStatus())) {
            return;
        }
        if (delivery.getNotificationOutboxId() != null) {
            ReportRun run = findRun(delivery.getRunId(), tenantId);
            ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
            assertPreviewFresh(run, preview);
            if (notificationOutboxMapper.replayReport(tenantId, delivery.getNotificationOutboxId()) == 0) {
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
        delivery.setAttemptCount(0);
        delivery.setDeliveryStatus("RETRY");
        delivery.setLastErrorCode(null);
        delivery.setLastErrorMessage(null);
        updateDeliveryChecked(delivery);
        ReportRun run = findRun(delivery.getRunId(), tenantId);
        ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
        assertPreviewFresh(run, preview);
        ReportRecipientPreview recipient = preview.getRecipients().stream()
                .filter(item -> delivery.getRecipientUserId().equals(item.getRecipientUserId()))
                .findFirst().orElseThrow(() -> BusinessException.of(403, "error.managementReport.scopeChanged"));
        if (!"ALLOW".equals(recipient.getScopeDecision())) {
            throw BusinessException.of(403, "error.managementReport.scopeChanged");
        }
        deliveryIssueService.issue(run, delivery, recipient, resolveArtifact(run, delivery));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long deliveryId) {
        requireAdmin();
        String tenantId = requireTenant();
        ReportDelivery delivery = findRequired(deliveryId, tenantId);
        if ("CANCELLED".equals(delivery.getDeliveryStatus())) {
            return;
        }
        delivery.setDeliveryStatus("CANCELLED");
        delivery.setLinkExpiresAt(now());
        delivery.setLinkTokenHash(null);
        delivery.setLastErrorCode("DELIVERY_CANCELLED");
        delivery.setLastErrorMessage("管理者により取消されました");
        updateDeliveryChecked(delivery);
        if (delivery.getNotificationOutboxId() != null) {
            int cancelled = notificationOutboxMapper.cancelPendingReport(
                    tenantId, delivery.getNotificationOutboxId());
            if (cancelled == 0) {
                NotificationOutbox outbox = notificationOutboxMapper.selectByIdForDispatch(
                        tenantId, delivery.getNotificationOutboxId());
                if (outbox != null && "PROCESSING".equals(outbox.getStatus())) {
                    if (notificationOutboxMapper.markReconciliationRequired(
                            tenantId, outbox.getId(), "REPORT_DELIVERY_CANCELLED_DURING_PROCESSING") == 0) {
                        throw new IllegalStateException("取消済みレポート配布の再照合登録に失敗しました");
                    }
                }
            }
        }
    }

    @Override
    public List<ReportDelivery> listByRun(Long runId) {
        String tenantId = requireTenant();
        ReportRun run = findRun(runId, tenantId);
        if (run == null) {
            throw BusinessException.of(404, "error.managementReport.runNotFound");
        }
        snapshotService.assertAccessible(run);
        return deliveryMapper.selectList(new QueryWrapper<ReportDelivery>()
                .eq("tenant_id", tenantId).eq("run_id", runId).orderByAsc("id"));
    }

    private ResolvedDownload resolveDownload(Long deliveryId, String token, String format) {
        String tenantId = requireTenant();
        ReportDelivery delivery;
        if (token == null || token.isBlank()) {
            // 認証済みaction endpoint: deliveryId + ログイン主体で解決する（token不要）。
            delivery = deliveryMapper.selectOne(new QueryWrapper<ReportDelivery>()
                    .eq("tenant_id", tenantId).eq("id", deliveryId));
        } else {
            delivery = deliveryMapper.selectByLinkTokenHash(tenantId, sha256(token));
            if (delivery == null || !deliveryId.equals(delivery.getId())
                    || delivery.getLinkTokenHash() == null
                    || !delivery.getLinkTokenHash().equals(sha256(token))) {
                throw BusinessException.of(403, "error.managementReport.linkInvalid");
            }
        }
        if (delivery == null || !deliveryId.equals(delivery.getId())) {
            throw BusinessException.of(403, "error.managementReport.linkInvalid");
        }
        if (delivery.getTenantId() == null || !tenantId.equals(delivery.getTenantId())) {
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
        ReportRun run = findRun(delivery.getRunId(), tenantId);
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
        return new ResolvedDownload(documentId, versionNo, fileName, contentType);
    }

    private void requirePreviewHash(String previewHash) {
        if (previewHash == null || previewHash.isBlank()) {
            throw BusinessException.of(400, "error.managementReport.recipientPreviewRequired");
        }
    }

    private void assertPreviewHashMatches(String requiredPreviewHash, String currentPreviewHash) {
        if (currentPreviewHash == null || currentPreviewHash.isBlank()
                || !requiredPreviewHash.equals(currentPreviewHash)) {
            throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
        }
    }

    private void assertScheduledContext(ReportRun run, ReportScheduledDeliveryContext systemContext) {
        if (!"SYSTEM_PRINCIPAL".equals(run.getPrincipalType())) {
            throw BusinessException.of(403, "error.managementReport.systemContextRequired");
        }
        if (run.getScheduleId() == null || !run.getScheduleId().equals(systemContext.scheduleId())) {
            throw BusinessException.of(403, "error.managementReport.systemContextMismatch");
        }
        if (run.getPrincipalUserId() == null || !run.getPrincipalUserId().equals(systemContext.principalUserId())) {
            throw BusinessException.of(403, "error.managementReport.systemContextMismatch");
        }
    }

    private ReportDelivery find(Long runId, Long userId, String tenantId) {
        return deliveryMapper.selectOne(new QueryWrapper<ReportDelivery>()
                .eq("tenant_id", tenantId).eq("run_id", runId).eq("recipient_user_id", userId));
    }

    private ReportDelivery findRequired(Long deliveryId, String tenantId) {
        ReportDelivery delivery = deliveryMapper.selectOne(new QueryWrapper<ReportDelivery>()
                .eq("tenant_id", tenantId).eq("id", deliveryId));
        if (delivery == null) {
            throw BusinessException.of(404, "error.managementReport.deliveryNotFound");
        }
        return delivery;
    }

    private ReportDelivery findRequiredForReplay(Long deliveryId, String tenantId) {
        ReportDelivery delivery = deliveryMapper.selectByIdForReplay(tenantId, deliveryId);
        if (delivery == null || delivery.getTenantId() == null || !tenantId.equals(delivery.getTenantId())) {
            throw BusinessException.of(404, "error.managementReport.deliveryNotFound");
        }
        return delivery;
    }

    private ReportRun findRun(Long runId, String tenantId) {
        ReportRun run = runMapper.selectOne(new QueryWrapper<ReportRun>()
                .eq("tenant_id", tenantId).eq("id", runId));
        if (run == null) {
            throw BusinessException.of(404, "error.managementReport.runNotFound");
        }
        return run;
    }

    private ReportDocumentArtifact resolveArtifact(ReportRun run, ReportDelivery delivery) {
        String tenantId = requireTenant();
        if (delivery.getDocumentId() != null && delivery.getDocumentVersionNo() != null) {
            Document document = documentMapper.selectOne(new QueryWrapper<Document>()
                    .eq("tenant_id", tenantId).eq("id", delivery.getDocumentId()));
            DocumentVersion version = documentVersionMapper.selectOne(new QueryWrapper<DocumentVersion>()
                    .eq("tenant_id", tenantId)
                    .eq("document_id", delivery.getDocumentId())
                    .eq("version_no", delivery.getDocumentVersionNo()));
            if (document != null && version != null
                    && documentService.getVersionStorageKey(document.getId(), version.getVersionNo()) != null) {
                return new ReportDocumentArtifact(run.getId(), "PDF", version.getSha256(), document, version);
            }
        }
        return documentRegistrar.registerArtifact(run.getId(), "PDF");
    }

    private void rotateDownloadToken(ReportDelivery delivery) {
        // 旧raw tokenを即時失効させる。通知/outboxへは載せない。
        delivery.setLinkTokenHash(sha256(randomToken()));
        delivery.setLinkExpiresAt(now().plusDays(LINK_DAYS));
        delivery.setReauthRequired(1);
        delivery.setReauthenticatedAt(null);
    }

    private void reconcileReplayConflict(ReportDelivery delivery, String errorCode) {
        String tenantId = requireTenant();
        boolean outboxMarked = delivery.getNotificationOutboxId() != null
                && notificationOutboxMapper.markReconciliationRequired(
                tenantId, delivery.getNotificationOutboxId(), errorCode) > 0;
        if (!outboxMarked) {
            delivery.setLastErrorCode(errorCode + "_OUTBOX_MISSING");
            delivery.setLastErrorMessage("通知outbox参照を確認できないため配布側で再照合を保留しています");
            updateDeliveryChecked(delivery);
            return;
        }
        delivery.setLastErrorCode(errorCode);
        delivery.setLastErrorMessage("通知outboxの状態競合を検出したため再照合が必要です");
        updateDeliveryChecked(delivery);
    }

    private void updateDeliveryChecked(ReportDelivery delivery) {
        String tenantId = requireTenant();
        int updated = deliveryMapper.update(delivery, new UpdateWrapper<ReportDelivery>()
                .eq("tenant_id", tenantId)
                .eq("id", delivery.getId()));
        if (updated > 0) {
            return;
        }
        ReportDelivery current = delivery.getId() == null ? null : deliveryMapper.selectOne(
                new QueryWrapper<ReportDelivery>().eq("tenant_id", tenantId).eq("id", delivery.getId()));
        boolean terminal = "SENT".equals(delivery.getDeliveryStatus())
                || "FAILED".equals(delivery.getDeliveryStatus())
                || "CANCELLED".equals(delivery.getDeliveryStatus());
        if (terminal && current != null && java.util.Objects.equals(current.getDeliveryStatus(), delivery.getDeliveryStatus())
                && java.util.Objects.equals(current.getNotificationOutboxId(), delivery.getNotificationOutboxId())
                && java.util.Objects.equals(current.getLastErrorCode(), delivery.getLastErrorCode())
                && java.util.Objects.equals(current.getLastErrorMessage(), delivery.getLastErrorMessage())) {
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
        if (id == null) {
            throw BusinessException.of(401, "error.unauthorized");
        }
        return id;
    }

    private void requireAdmin() {
        if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.managementReport.adminRequired");
        }
    }

    private LocalDateTime now() {
        return timezoneResolver.now(requireTenant());
    }

    private String requireTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
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

    private record ResolvedDownload(Long documentId, Integer versionNo, String fileName, String contentType) {
    }
}
