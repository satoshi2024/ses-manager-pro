package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.report.ReportDeliveryResult;
import com.ses.dto.report.ReportDownload;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.dto.report.ReportScheduledDeliveryContext;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.entity.SysUser;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.mapper.ReportRunMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.service.DocumentService;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportDocumentService;
import com.ses.service.report.ReportDeliveryDocumentRegistrar;
import com.ses.service.report.ReportDeliveryIssueService;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * delivery状態と通知dedupeを管理する。plaintext tokenはDBへ保存せず、通知linkへ一度だけ載せる。
 * 文書生成・storage read/writeは短いDB TXの外で実行する。
 */
@Service
@RequiredArgsConstructor
public class ReportDeliveryServiceImpl implements ReportDeliveryService {

    private static final String TENANT_ID = "default";
    private static final int MAX_ATTEMPTS = 5;
    private static final int REAUTH_MINUTES = 10;
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
    private final PasswordEncoder passwordEncoder;
    private final AccountingTimezoneResolver timezoneResolver;

    @Override
    public ReportDeliveryResult deliverUser(Long runId, String requiredPreviewHash) {
        requirePreviewHash(requiredPreviewHash);
        return AccountingTenantContextHolder.runWithTenant(TENANT_ID, tenantZone(), () -> {
            ReportRun run = runMapper.selectById(runId);
            requireReady(run);
            snapshotService.assertAccessible(run);
            ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
            assertPreviewHashMatches(requiredPreviewHash, preview.getPreviewHash());
            return deliverRecipients(run, preview);
        });
    }

    @Override
    public ReportDeliveryResult deliverScheduled(Long runId, ReportScheduledDeliveryContext systemContext) {
        if (systemContext == null) {
            throw BusinessException.of(400, "error.managementReport.systemContextRequired");
        }
        return AccountingTenantContextHolder.runWithTenant(TENANT_ID, tenantZone(), () -> {
            ReportRun run = runMapper.selectById(runId);
            requireReady(run);
            assertScheduledContext(run, systemContext);
            snapshotService.assertAccessible(run);
            ReportRecipientPreviewResult preview = recipientPreviewService.previewForRun(run);
            return deliverRecipients(run, preview);
        });
    }

    private ReportDeliveryResult deliverRecipients(ReportRun run, ReportRecipientPreviewResult preview) {
        ReportDocumentArtifact artifact = null;
        List<ReportDelivery> deliveries = new ArrayList<>();
        for (ReportRecipientPreview recipient : preview.getRecipients()) {
            if (!"ALLOW".equals(recipient.getScopeDecision())) {
                continue;
            }
            ReportDelivery delivery = find(run.getId(), recipient.getRecipientUserId());
            if (delivery != null && !"CANCELLED".equals(delivery.getDeliveryStatus())) {
                deliveries.add(delivery);
                continue;
            }
            if (artifact == null) {
                artifact = documentRegistrar.registerArtifact(run.getId(), "PDF");
            }
            deliveries.add(deliveryIssueService.issue(run, delivery, recipient, artifact));
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
    @Transactional(rollbackFor = Exception.class)
    public void retry(Long deliveryId) {
        requireAdmin();
        ReportDelivery delivery = findRequired(deliveryId);
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
        ReportDocumentArtifact artifact = new ReportDocumentArtifact(run.getId(), "PDF", null, null, null);
        deliveryIssueService.issue(run, delivery, recipient, artifact);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualReplay(Long deliveryId) {
        requireAdmin();
        ReportDelivery delivery = findRequired(deliveryId);
        if (!"FAILED".equals(delivery.getDeliveryStatus())) {
            return;
        }
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
        delivery.setAttemptCount(0);
        delivery.setDeliveryStatus("RETRY");
        delivery.setLastErrorCode(null);
        delivery.setLastErrorMessage(null);
        deliveryMapper.updateById(delivery);
        retry(deliveryId);
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
        deliveryMapper.updateById(delivery);
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

    private ResolvedDownload resolveDownload(Long deliveryId, String token, String format) {
        ReportDelivery delivery = findRequired(deliveryId);
        Long userId = currentUserId();
        if (!userId.equals(delivery.getRecipientUserId())) {
            throw BusinessException.of(403, "error.managementReport.scopeDenied");
        }
        if ("CANCELLED".equals(delivery.getDeliveryStatus())) {
            throw BusinessException.of(403, "error.managementReport.deliveryCancelled");
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

    private ReportDelivery find(Long runId, Long userId) {
        return deliveryMapper.selectOne(new QueryWrapper<ReportDelivery>()
                .eq("run_id", runId).eq("recipient_user_id", userId));
    }

    private ReportDelivery findRequired(Long deliveryId) {
        ReportDelivery delivery = deliveryMapper.selectById(deliveryId);
        if (delivery == null) {
            throw BusinessException.of(404, "error.managementReport.deliveryNotFound");
        }
        return delivery;
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
        return timezoneResolver.now(TENANT_ID);
    }

    private ZoneId tenantZone() {
        return timezoneResolver.resolve(TENANT_ID);
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
