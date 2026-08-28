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
import com.ses.service.DocumentService;
import com.ses.service.NotificationService;
import com.ses.service.report.ReportDocumentService;
import com.ses.service.report.ReportDeliveryDocumentRegistrar;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.accounting.AccountingTimezoneResolver;
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
    private DocumentService archiveService;
    private NotificationService notificationService;
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private ReportDeliveryServiceImpl service;

    @BeforeEach
    void setUp() {
        runMapper = mock(ReportRunMapper.class);
        deliveryMapper = mock(ReportDeliveryMapper.class);
        notificationOutboxMapper = mock(NotificationOutboxMapper.class);
        userMapper = mock(SysUserMapper.class);
        previewService = mock(ReportRecipientPreviewService.class);
        snapshotService = mock(ReportSnapshotService.class);
        documentService = mock(ReportDocumentService.class);
        documentRegistrar = mock(ReportDeliveryDocumentRegistrar.class);
        archiveService = mock(DocumentService.class);
        notificationService = mock(NotificationService.class);
        when(notificationService.publishToUserAndGetOutboxId(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(99L);
        passwordEncoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve("default")).thenReturn(java.time.ZoneId.of("Asia/Tokyo"));
        when(timezoneResolver.now("default")).thenAnswer(invocation -> LocalDateTime.now());
        service = new ReportDeliveryServiceImpl(runMapper, deliveryMapper, notificationOutboxMapper, userMapper, previewService,
                snapshotService, documentService, documentRegistrar, archiveService, notificationService, passwordEncoder,
                new ObjectMapper(), timezoneResolver);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deliveryUsesPreviewAndPublishesOneInAppLink() {
        ReportRun run = readyRun();
        when(runMapper.selectById(10L)).thenReturn(run);
        ReportRecipientPreview recipient = new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope");
        when(previewService.previewForRun(run)).thenReturn(preview(recipient));
        Document document = new Document();
        document.setId(20L);
        DocumentVersion version = new DocumentVersion();
        version.setVersionNo(1);
        ReportDocumentArtifact artifact = new ReportDocumentArtifact(10L, "PDF", "hash", document, version);
        when(documentRegistrar.registerArtifact(10L, "PDF")).thenReturn(artifact);

        ReportDeliveryResult result = service.deliver(10L, "preview-hash");

        assertThat(result.getDeliveries()).hasSize(1);
        assertThat(result.getDeliveries().get(0).getLinkTokenHash()).hasSize(64);
        verify(notificationService).publishToUserAndGetOutboxId(eq(2L), eq("MANAGEMENT_REPORT"), any(), any(),
                contains("/download?token="), any(), eq("management-report"));
    }

    @Test
    void deliverはENQUEUED中の既存deliveryを再配布しない() {
        ReportRun run = readyRun();
        when(runMapper.selectById(10L)).thenReturn(run);
        ReportRecipientPreview recipient = new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope");
        when(previewService.previewForRun(run)).thenReturn(preview(recipient));
        ReportDelivery existing = new ReportDelivery();
        existing.setId(71L);
        existing.setRunId(10L);
        existing.setRecipientUserId(2L);
        existing.setDeliveryStatus("ENQUEUED");
        when(deliveryMapper.selectOne(any())).thenReturn(existing);

        ReportDeliveryResult result = service.deliver(10L, "preview-hash");

        assertThat(result.getDeliveries()).containsExactly(existing);
        verify(documentRegistrar, never()).registerArtifact(anyLong(), anyString());
        verifyNoInteractions(notificationService);
    }

    @Test
    void downloadRejectsExpiredLinkBeforeOpeningDocument() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setRecipientUserId(1L);
        delivery.setLinkTokenHash("wrong");
        delivery.setLinkExpiresAt(LocalDateTime.now().minusMinutes(1));
        delivery.setReauthRequired(1);
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);

        assertThatThrownBy(() -> service.download(7L, "token", "PDF"))
                .hasMessageContaining("error.managementReport.linkInvalid");
        verifyNoInteractions(archiveService);
    }

    @Test
    void reauthenticationIsRecordedOnlyAfterPasswordVerification() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRecipientUserId(1L);
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setPassword("encoded");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);

        service.reauthenticate(7L, "secret");

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
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);
        when(runMapper.selectById(10L)).thenReturn(readyRun());
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                1L, "マネージャー", "DENY", "RECIPIENT_SCOPE_MISMATCH", "changed");
        when(previewService.previewForRun(any())).thenReturn(preview(recipient));

        assertThatThrownBy(() -> service.download(7L, "token", "PDF"))
                .hasMessageContaining("error.managementReport.scopeChanged");
        verifyNoInteractions(archiveService);
    }

    @Test
    void downloadはownerと別の許可済みmanagerにも利用させる() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("2", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_マネージャー"))));
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setRunId(10L);
        delivery.setRecipientUserId(2L);
        delivery.setDocumentId(20L);
        delivery.setDocumentVersionNo(1);
        delivery.setLinkTokenHash(sha256("token"));
        delivery.setLinkExpiresAt(LocalDateTime.now().plusDays(1));
        delivery.setReauthRequired(1);
        delivery.setReauthenticatedAt(LocalDateTime.now());
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);
        ReportRun run = readyRun();
        run.setScopeOwnerType("ORGANIZATION");
        run.setScopeOwnerId(1L);
        run.setOrganizationScopeJson("{\"companyWide\":false,\"organizationIds\":[10],\"directUserIds\":[]}");
        when(runMapper.selectById(10L)).thenReturn(run);
        when(previewService.previewForRun(run)).thenReturn(preview(
                new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "recipient-scope")));
        when(archiveService.getVersionStorageKey(20L, 1)).thenReturn("published/report.pdf");
        when(archiveService.download(20L, 1)).thenReturn(new ByteArrayInputStream("pdf".getBytes(StandardCharsets.UTF_8)));

        ReportDownload result = service.download(7L, "token", "PDF");

        assertThat(result.getFileName()).isEqualTo("management-report.pdf");
        verify(snapshotService).scopeSnapshotOf(run);
        verify(snapshotService, never()).assertAccessible(run);
        verify(archiveService).download(20L, 1);
    }

    @Test
    void retryが5回到達時にdeliveryをDLQへ移す() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setAttemptCount(5);
        delivery.setDeliveryStatus("RETRY");
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);

        service.retry(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("FAILED");
        assertThat(delivery.getLastErrorCode()).isEqualTo("DELIVERY_DLQ");
        verify(deliveryMapper).updateById(delivery);
        verifyNoInteractions(previewService, notificationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENQUEUED", "PROCESSING", "PENDING", "SENT"})
    void retryはdispatch中または完了済みdeliveryを再送しない(String status) {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setAttemptCount(1);
        delivery.setDeliveryStatus(status);
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);

        service.retry(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo(status);
        verify(deliveryMapper, never()).updateById(any(ReportDelivery.class));
        verifyNoInteractions(previewService, notificationService, notificationOutboxMapper);
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
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);
        when(runMapper.selectById(10L)).thenReturn(readyRun());
        when(previewService.previewForRun(any())).thenReturn(
                preview(new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope")));
        when(notificationOutboxMapper.requeueReport(88L)).thenReturn(1);

        service.retry(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("ENQUEUED");
        verify(notificationOutboxMapper).requeueReport(88L);
        verifyNoInteractions(notificationService);
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
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);
        when(runMapper.selectById(10L)).thenReturn(readyRun());
        when(previewService.previewForRun(any())).thenReturn(
                preview(new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope")));

        service.manualReplay(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("ENQUEUED");
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        verify(notificationService).publishToUserAndGetOutboxId(eq(2L), eq("MANAGEMENT_REPORT"), any(), any(),
                contains("/download?token="), any(), eq("management-report"));
    }

    @Test
    void manualReplayはDLQの既存outboxを再利用しdedupe衝突を起こさない() {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setId(7L);
        delivery.setAttemptCount(5);
        delivery.setDeliveryStatus("FAILED");
        delivery.setNotificationOutboxId(88L);
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);
        when(notificationOutboxMapper.replayReport(88L)).thenReturn(1);

        service.manualReplay(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("ENQUEUED");
        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        verify(notificationOutboxMapper).replayReport(88L);
        verify(deliveryMapper).updateById(delivery);
        verifyNoInteractions(notificationService, previewService, runMapper);
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
        when(deliveryMapper.selectById(7L)).thenReturn(delivery);

        service.cancel(7L);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("CANCELLED");
        assertThat(delivery.getLinkTokenHash()).isNull();
        verify(deliveryMapper).updateById(delivery);

        assertThatThrownBy(() -> service.download(7L, "token", "PDF"))
                .hasMessageContaining("error.managementReport.deliveryCancelled");
        verifyNoInteractions(archiveService);
    }

    private ReportRecipientPreviewResult preview(ReportRecipientPreview recipient) {
        return new ReportRecipientPreviewResult("preview-hash", "APPROVED_SCOPE_CHECKED",
                LocalDateTime.now(), List.of(recipient));
    }

    private ReportRun readyRun() {
        ReportRun run = new ReportRun();
        run.setId(10L);
        run.setStatus("SUCCEEDED");
        run.setPeriodFrom(LocalDate.of(2026, 8, 1));
        run.setPeriodTo(LocalDate.of(2026, 8, 31));
        run.setTemplateVersionId(3L);
        run.setScopeHash("scope");
        run.setOrganizationScopeJson("{\"companyWide\":true,\"organizationIds\":[]}");
        return run;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
