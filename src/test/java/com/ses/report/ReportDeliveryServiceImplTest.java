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
import com.ses.mapper.ReportRunMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.DocumentService;
import com.ses.service.NotificationService;
import com.ses.service.report.ReportDocumentService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.impl.ReportDeliveryServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
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
    private SysUserMapper userMapper;
    private ReportRecipientPreviewService previewService;
    private ReportDocumentService documentService;
    private DocumentService archiveService;
    private NotificationService notificationService;
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private ReportDeliveryServiceImpl service;

    @BeforeEach
    void setUp() {
        runMapper = mock(ReportRunMapper.class);
        deliveryMapper = mock(ReportDeliveryMapper.class);
        userMapper = mock(SysUserMapper.class);
        previewService = mock(ReportRecipientPreviewService.class);
        documentService = mock(ReportDocumentService.class);
        archiveService = mock(DocumentService.class);
        notificationService = mock(NotificationService.class);
        passwordEncoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        service = new ReportDeliveryServiceImpl(runMapper, deliveryMapper, userMapper, previewService,
                documentService, archiveService, notificationService, passwordEncoder, new ObjectMapper());
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
        when(documentService.register(10L, "PDF")).thenReturn(artifact);

        ReportDeliveryResult result = service.deliver(10L, "preview-hash");

        assertThat(result.getDeliveries()).hasSize(1);
        assertThat(result.getDeliveries().get(0).getLinkTokenHash()).hasSize(64);
        verify(notificationService).publishToUser(eq(2L), eq("MANAGEMENT_REPORT"), any(), any(),
                contains("/download?token="), any(), eq("management-report"));
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
}
