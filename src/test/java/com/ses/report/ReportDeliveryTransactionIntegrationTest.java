package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.mapper.ReportRunMapper;
import com.ses.service.DocumentService;
import com.ses.service.NotificationService;
import com.ses.service.report.ReportDeliveryDocumentRegistrar;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.report.ReportDeliveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** 通知失敗を独立transactionへ隔離し、配布行をRETRYで確定できることを確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportDeliveryTransactionIntegrationTest {

    @Autowired
    private ReportDeliveryService reportDeliveryService;

    @Autowired
    private ReportRunMapper runMapper;

    @Autowired
    private ReportDeliveryMapper deliveryMapper;

    @MockBean
    private ReportSnapshotService snapshotService;

    @MockBean
    private ReportRecipientPreviewService recipientPreviewService;

    @MockBean
    private ReportDeliveryDocumentRegistrar documentRegistrar;

    @MockBean
    private DocumentService documentService;

    @MockBean
    private NotificationService notificationService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 通知失敗でもUnexpectedRollbackなく配布をRETRYへ確定しPROCESSINGを残さない() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "recipient-scope");
        ReportRecipientPreviewResult preview = new ReportRecipientPreviewResult(
                "preview-hash", "APPROVED_SCOPE_CHECKED", LocalDateTime.now(), List.of(recipient));
        ReportRun run = new ReportRun();
        run.setTenantId("default");
        run.setRunKey("nf10-transaction-run-" + System.nanoTime());
        run.setTemplateId(1L);
        run.setTemplateVersionId(1L);
        run.setSnapshotVersion(1);
        run.setPrincipalType("HUMAN");
        run.setScopeOwnerType("ORGANIZATION");
        run.setScopeOwnerId(1L);
        run.setOrganizationScopeJson("{\"companyWide\":true,\"organizationIds\":[]}");
        run.setScopePolicyVersion("scope-policy-approved-1");
        run.setScopeHash("scope-hash");
        run.setRecipientPreviewHash("preview-hash");
        run.setRecipientSnapshotJson(new ObjectMapper().writeValueAsString(List.of(recipient)));
        run.setPeriodFrom(LocalDate.of(2026, 8, 1));
        run.setPeriodTo(LocalDate.of(2026, 8, 31));
        run.setCutoffKind("GENERATED_AT");
        run.setAsOfAt(LocalDateTime.now());
        run.setTimezoneId("Asia/Tokyo");
        run.setStatus("SUCCEEDED");
        run.setSnapshotSchemaVersion("report-1.0");
        runMapper.insert(run);

        Document document = new Document();
        document.setId(9901L);
        DocumentVersion version = new DocumentVersion();
        version.setVersionNo(1);
        when(recipientPreviewService.previewForRun(any(ReportRun.class))).thenReturn(preview);
        when(documentRegistrar.registerArtifact(run.getId(), "PDF"))
                .thenReturn(new ReportDocumentArtifact(run.getId(), "PDF", "artifact-hash", document, version));
        when(documentService.getVersionStorageKey(anyLong(), anyInt())).thenReturn("published/report.pdf");
        doThrow(new IllegalStateException("notification database failure"))
                .when(notificationService).publishToUserAndGetOutboxIdWithoutDispatch(
                        anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> reportDeliveryService.deliver(run.getId(), "preview-hash"))
                .doesNotThrowAnyException();

        ReportDelivery delivery = deliveryMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReportDelivery>()
                .eq("run_id", run.getId()));
        assertThat(delivery).isNotNull();
        assertThat(delivery.getDeliveryStatus()).isEqualTo("RETRY");
        assertThat(delivery.getLastErrorCode()).isEqualTo("DELIVERY_FAILED");
        assertThat(delivery.getDocumentId()).isEqualTo(9901L);
    }
}
