package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.dto.report.ReportDeliveryResult;
import com.ses.dto.report.ReportDownload;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.entity.SysUser;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.mapper.ReportRunMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.DocumentService;
import com.ses.service.report.ReportDocumentService;
import com.ses.service.report.ReportDeliveryDocumentRegistrar;
import com.ses.service.report.ReportDeliveryIssueService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.report.impl.ReportDeliveryServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportDeliveryServiceImplTest {

    private ReportRunMapper runMapper;
    private ReportDeliveryMapper deliveryMapper;
    private NotificationOutboxMapper notificationOutboxMapper;
    private SysUserMapper userMapper;
    private ReportRecipientPreviewService previewService;
    private ReportSnapshotService snapshotService;
    private ReportDocumentService documentService;
    private ReportDeliveryDocumentRegistrar documentRegistrar;
    private ReportDeliveryIssueService deliveryIssueService;
    private DocumentService archiveService;
    private DocumentMapper documentMapper;
    private DocumentVersionMapper documentVersionMapper;
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private ReportDeliveryServiceImpl service;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        runMapper = mock(ReportRunMapper.class);
        deliveryMapper = mock(ReportDeliveryMapper.class);
        when(deliveryMapper.updateById(any(ReportDelivery.class))).thenReturn(1);
        notificationOutboxMapper = mock(NotificationOutboxMapper.class);
        userMapper = mock(SysUserMapper.class);
        previewService = mock(ReportRecipientPreviewService.class);
        snapshotService = mock(ReportSnapshotService.class);
        documentService = mock(ReportDocumentService.class);
        documentRegistrar = mock(ReportDeliveryDocumentRegistrar.class);
        deliveryIssueService = mock(ReportDeliveryIssueService.class);
        archiveService = mock(DocumentService.class);
        documentMapper = mock(DocumentMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        when(archiveService.getVersionStorageKey(anyLong(), anyInt())).thenReturn("published/report.pdf");
        passwordEncoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve("default")).thenReturn(java.time.ZoneId.of("Asia/Tokyo"));
        when(timezoneResolver.now("default")).thenAnswer(invocation -> LocalDateTime.now());
        service = new ReportDeliveryServiceImpl(runMapper, deliveryMapper, notificationOutboxMapper, userMapper, previewService,
                snapshotService, documentService, documentRegistrar, deliveryIssueService, archiveService,
                documentMapper, documentVersionMapper, passwordEncoder,
                new ObjectMapper(), timezoneResolver);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void deliveryUsesPreviewAndPublishesOneInAppLink() {
        ReportRun run = readyRun();
        when(runMapper.selectOne(any())).thenReturn(run);
        ReportRecipientPreview recipient = new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope");
        when(previewService.previewForRun(run)).thenReturn(preview(recipient));
        Document document = new Document();
        document.setId(20L);
        DocumentVersion version = new DocumentVersion();
        version.setVersionNo(1);
        ReportDocumentArtifact artifact = new ReportDocumentArtifact(10L, "PDF", "hash", document, version);
        when(documentRegistrar.registerArtifact(10L, "PDF")).thenReturn(artifact);
        ReportDelivery issued = new ReportDelivery();
        issued.setLinkTokenHash("a".repeat(64));
        issued.setDeliveryStatus("ENQUEUED");
        when(deliveryIssueService.issue(eq(run), isNull(), eq(recipient), eq(artifact))).thenReturn(issued);

        ReportDeliveryResult result = service.deliverUser(10L, "preview-hash");

        assertThat(result.getDeliveries()).hasSize(1);
        assertThat(result.getDeliveries().get(0).getLinkTokenHash()).hasSize(64);
        verify(deliveryIssueService).issue(eq(run), isNull(), eq(recipient), eq(artifact));
    }

    @Test
    void deliverUserはpreviewHash未指定を拒否する() {
        assertThatThrownBy(() -> service.deliverUser(10L, null))
                .hasMessageContaining("error.managementReport.recipientPreviewRequired");
        assertThatThrownBy(() -> service.deliverUser(10L, "  "))
                .hasMessageContaining("error.managementReport.recipientPreviewRequired");
    }

    @Test
    void deliverUserはstalePreviewHashを拒否する() {
        ReportRun run = readyRun();
        when(runMapper.selectById(10L)).thenReturn(run);
        when(previewService.previewForRun(run)).thenReturn(preview(
                new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope")));

        assertThatThrownBy(() -> service.deliverUser(10L, "stale-hash"))
                .hasMessageContaining("error.managementReport.recipientPreviewStale");
        verifyNoInteractions(documentRegistrar, deliveryIssueService);
    }

    @Test
    void deliverはENQUEUED中の既存deliveryを再配布しない() {
        ReportRun run = readyRun();
        when(runMapper.selectOne(any())).thenReturn(run);
        ReportRecipientPreview recipient = new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope");
        when(previewService.previewForRun(run)).thenReturn(preview(recipient));
        ReportDelivery existing = new ReportDelivery();
        existing.setId(71L);
        existing.setRunId(10L);
        existing.setRecipientUserId(2L);
        existing.setDeliveryStatus("ENQUEUED");
        when(deliveryMapper.selectOne(any())).thenReturn(existing);

        ReportDeliveryResult result = service.deliverUser(10L, "preview-hash");

        assertThat(result.getDeliveries()).containsExactly(existing);
        verify(documentRegistrar, never()).registerArtifact(anyLong(), anyString());
        verifyNoInteractions(deliveryIssueService);
    }

    @Test
    void deliverはCANCELLEDの既存deliveryを再issueする() {
        ReportRun run = readyRun();
        when(runMapper.selectOne(any())).thenReturn(run);
        ReportRecipientPreview recipient = new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope");
        when(previewService.previewForRun(run)).thenReturn(preview(recipient));
        ReportDelivery existing = new ReportDelivery();
        existing.setId(71L);
        existing.setRunId(10L);
        existing.setRecipientUserId(2L);
        existing.setDeliveryStatus("CANCELLED");
        existing.setAttemptCount(1);
        when(deliveryMapper.selectOne(any())).thenReturn(existing);
        Document document = new Document();
        document.setId(20L);
        DocumentVersion version = new DocumentVersion();
        version.setVersionNo(1);
        when(documentRegistrar.registerArtifact(10L, "PDF"))
                .thenReturn(new ReportDocumentArtifact(10L, "PDF", "hash", document, version));
        ReportDelivery reissued = new ReportDelivery();
        reissued.setDeliveryStatus("ENQUEUED");
        reissued.setLinkTokenHash("b".repeat(64));
        when(deliveryIssueService.issue(eq(run), eq(existing), eq(recipient), any())).thenReturn(reissued);

        ReportDeliveryResult result = service.deliverUser(10L, "preview-hash");

        assertThat(result.getDeliveries()).hasSize(1);
        assertThat(result.getDeliveries().get(0).getDeliveryStatus()).isEqualTo("ENQUEUED");
        assertThat(result.getDeliveries().get(0).getLinkTokenHash()).hasSize(64);
        verify(deliveryIssueService).issue(eq(run), eq(existing), eq(recipient), any());
    }

    @Test
    void downloadRejectsExpiredLinkBeforeOpeningDocument() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setRecipientUserId(1L);
        delivery.setLinkTokenHash(sha256("token"));
        delivery.setLinkExpiresAt(LocalDateTime.now().minusMinutes(1));
        delivery.setReauthRequired(1);
        delivery.setTenantId("default");
        when(deliveryMapper.selectByLinkTokenHash(sha256("token"))).thenReturn(delivery);
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);

        assertThatThrownBy(() -> service.download(7L, "token", "PDF"))
                .hasMessageContaining("error.managementReport.linkExpired");
        verifyNoInteractions(archiveService);
    }

    @Test
    void downloadはdeliveryIdや誤tokenをbearerとして受け付けない() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setRecipientUserId(1L);
        delivery.setLinkTokenHash(sha256("actual-token"));
        delivery.setLinkExpiresAt(LocalDateTime.now().plusDays(1));
        delivery.setReauthRequired(0);
        when(deliveryMapper.selectByLinkTokenHash(sha256("7"))).thenReturn(null);
        when(deliveryMapper.selectByLinkTokenHash(sha256("wrong-token"))).thenReturn(null);

        assertThatThrownBy(() -> service.download(7L, "7", "PDF"))
                .hasMessageContaining("error.managementReport.linkInvalid");
        assertThatThrownBy(() -> service.download(7L, "wrong-token", "PDF"))
                .hasMessageContaining("error.managementReport.linkInvalid");
        verifyNoInteractions(archiveService);
    }

    @Test
    void reauthenticationIsRecordedOnlyAfterPasswordVerification() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRecipientUserId(1L);
        delivery.setTenantId("default");
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setPassword("encoded");
        when(userMapper.selectByIdAndTenant(1L, "default")).thenReturn(user);
        when(passwordEncoder.matches("pass", "encoded")).thenReturn(true);

        service.reauthenticate(7L, "pass");

        assertThat(delivery.getReauthenticatedAt()).isNotNull();
        verify(deliveryMapper).updateById(delivery);
    }

    @Test
    void downloadRechecksCurrentRecipientScopeAndRejectsAfterOrganizationChange() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setRecipientUserId(1L);
        delivery.setLinkTokenHash(sha256("token"));
        delivery.setLinkExpiresAt(LocalDateTime.now().plusDays(1));
        delivery.setReauthRequired(1);
        delivery.setReauthenticatedAt(LocalDateTime.now());
        delivery.setTenantId("default");
        when(deliveryMapper.selectByLinkTokenHash(anyString())).thenReturn(delivery);
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);
        when(runMapper.selectOne(any())).thenReturn(readyRun());
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                1L, "マネージャー", "DENY", "RECIPIENT_SCOPE_MISMATCH", "changed");
        when(previewService.previewForRun(any())).thenReturn(preview(recipient));

        assertThatThrownBy(() -> service.download(7L, "token", "PDF"))
                .hasMessageContaining("error.managementReport.scopeChanged");
        verifyNoInteractions(archiveService);
    }

    @Test
    void downloadはownerと別の許可済みmanagerにも利用させる() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setRecipientUserId(1L);
        delivery.setDocumentId(20L);
        delivery.setDocumentVersionNo(1);
        delivery.setLinkTokenHash(sha256("token"));
        delivery.setRecipientScopeHash("recipient-scope");
        delivery.setLinkExpiresAt(LocalDateTime.now().plusDays(1));
        delivery.setReauthRequired(1);
        delivery.setReauthenticatedAt(LocalDateTime.now());
        delivery.setTenantId("default");
        when(deliveryMapper.selectByLinkTokenHash(anyString())).thenReturn(delivery);
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);
        ReportRun run = readyRun();
        run.setTenantId("default");
        run.setScopeOwnerType("ORGANIZATION");
        run.setScopeOwnerId(1L);
        run.setOrganizationScopeJson("{\"companyWide\":false,\"organizationIds\":[10],\"directUserIds\":[]}");
        when(runMapper.selectOne(any())).thenReturn(run);
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                1L, "マネージャー", "ALLOW", "SCOPE_MATCH", "recipient-scope");
        when(previewService.previewForRun(any())).thenReturn(preview(recipient));
        when(archiveService.getVersionStorageKey(20L, 1)).thenReturn("published/report.pdf");
        when(archiveService.download(20L, 1)).thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        ReportDownload download = service.download(7L, "token", "PDF");

        assertThat(download.getFileName()).isEqualTo("management-report.pdf");
        verify(snapshotService).scopeSnapshotOf(run);
        verify(snapshotService, never()).assertAccessible(run);
        verify(archiveService).download(20L, 1);
        verify(deliveryIssueService).markDownloaded(7L);
    }

    @Test
    void retry5回到達時にdeliveryをDLQへ移行() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setAttemptCount(5);
        delivery.setDeliveryStatus("RETRY");
        delivery.setTenantId("default");
        when(deliveryMapper.selectByIdForReplay(7L)).thenReturn(delivery);
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);

        service.retry(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("FAILED");
        assertThat(delivery.getLastErrorCode()).isEqualTo("DELIVERY_DLQ");
        verify(deliveryMapper).updateById(delivery);
        verifyNoInteractions(previewService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENQUEUED", "PROCESSING", "SENT", "PENDING"})
    void retryはdispatch中または完了済みdeliveryを再送しない(String status) {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setAttemptCount(1);
        delivery.setDeliveryStatus(status);
        delivery.setTenantId("default");
        when(deliveryMapper.selectByIdForReplay(7L)).thenReturn(delivery);
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);

        service.retry(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo(status);
        verify(deliveryMapper, never()).updateById(any(ReportDelivery.class));
        verifyNoInteractions(previewService, notificationOutboxMapper);
    }

    @Test
    void retryはRETRYの既存outboxを再利用し新しい通知を発行しない() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setRecipientUserId(2L);
        delivery.setAttemptCount(2);
        delivery.setDeliveryStatus("RETRY");
        delivery.setNotificationOutboxId(88L);
        delivery.setTenantId("default");
        when(deliveryMapper.selectByIdForReplay(7L)).thenReturn(delivery);
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);
        when(runMapper.selectOne(any())).thenReturn(readyRun());
        when(previewService.previewForRun(any())).thenReturn(
                preview(new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope")));
        when(notificationOutboxMapper.requeueReport("default", 88L)).thenReturn(1);

        service.retry(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("ENQUEUED");
        verify(notificationOutboxMapper).requeueReport("default", 88L);
        verifyNoInteractions(documentRegistrar, deliveryIssueService);
    }

    @Test
    void manualReplayはDLQ前のdeliveryをscope再確認後に再送する() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setRecipientUserId(2L);
        delivery.setAttemptCount(5);
        delivery.setDeliveryStatus("FAILED");
        delivery.setDocumentId(20L);
        delivery.setDocumentVersionNo(1);
        delivery.setTenantId("default");
        when(deliveryMapper.selectByIdForReplay(7L)).thenReturn(delivery);
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);
        when(runMapper.selectOne(any())).thenReturn(readyRun());
        when(previewService.previewForRun(any())).thenReturn(
                preview(new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope")));
        Document document = new Document();
        document.setId(20L);
        DocumentVersion version = new DocumentVersion();
        version.setVersionNo(1);
        when(documentMapper.selectById(20L)).thenReturn(document);
        when(documentVersionMapper.selectOne(any())).thenReturn(version);
        when(deliveryIssueService.issue(any(), eq(delivery), any(), any())).thenAnswer(invocation -> {
            ReportDelivery target = invocation.getArgument(1);
            target.setDeliveryStatus("ENQUEUED");
            target.setAttemptCount(1);
            return target;
        });

        service.manualReplay(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("ENQUEUED");
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        verify(deliveryIssueService).issue(any(), eq(delivery), any(), any());
    }

    @Test
    void manualReplayはDLQの既存outboxを再利用しdedupe衝突を起こさない() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setAttemptCount(5);
        delivery.setDeliveryStatus("FAILED");
        delivery.setNotificationOutboxId(88L);
        delivery.setTenantId("default");
        when(deliveryMapper.selectByIdForReplay(7L)).thenReturn(delivery);
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);
        when(runMapper.selectOne(any())).thenReturn(readyRun());
        when(previewService.previewForRun(any())).thenReturn(
                preview(new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope")));
        when(notificationOutboxMapper.replayReport("default", 88L)).thenReturn(1);

        service.manualReplay(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("ENQUEUED");
        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        verify(notificationOutboxMapper).replayReport("default", 88L);
        verify(deliveryMapper).updateById(delivery);
        verifyNoInteractions(deliveryIssueService);
    }

    @Test
    void cancelはtokenを失効させdownloadを拒否する() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setRecipientUserId(1L);
        delivery.setLinkTokenHash(sha256("token"));
        delivery.setLinkExpiresAt(LocalDateTime.now().plusDays(1));
        delivery.setDeliveryStatus("ENQUEUED");
        delivery.setTenantId("default");
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);
        when(deliveryMapper.selectByLinkTokenHash(anyString())).thenReturn(delivery);

        service.cancel(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("CANCELLED");
        assertThat(delivery.getLinkTokenHash()).isNull();
        verify(deliveryMapper).updateById(delivery);

        assertThatThrownBy(() -> service.download(7L, "token", "PDF"))
                .hasMessageContaining("error.managementReport.linkInvalid");
        verifyNoInteractions(archiveService);
    }

    private ReportRun readyRun() {
        ReportRun run = new ReportRun();
        run.setId(10L);
        run.setTenantId("default");
        run.setStatus("SUCCEEDED");
        run.setPeriodFrom(LocalDate.of(2026, 8, 1));
        run.setPeriodTo(LocalDate.of(2026, 8, 31));
        run.setScopeOwnerType("COMPANY");
        run.setScopeOwnerId(1L);
        run.setTemplateVersionId(3L);
        run.setScopeHash("scope");
        run.setOrganizationScopeJson("{\"companyWide\":true,\"organizationIds\":[]}");
        run.setRecipientPreviewHash("preview-hash");
        try {
            run.setRecipientSnapshotJson(new ObjectMapper().writeValueAsString(List.of(
                    new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope"))));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return run;
    }

    private ReportRecipientPreviewResult preview(ReportRecipientPreview recipient) {
        return new ReportRecipientPreviewResult("preview-hash", "APPROVED_SCOPE_CHECKED",
                LocalDateTime.now(), List.of(recipient));
    }
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
