package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.exception.GlobalExceptionHandler;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.DocumentService;
import com.ses.service.certification.CertificationMasterService;
import com.ses.service.certification.EngineerCertificationService;
import com.ses.service.certificationlearninggap.CertificationEvidenceAccessService;
import com.ses.service.certificationlearninggap.CertificationLearningGapAiService;
import com.ses.service.certificationlearninggap.CertificationLearningGapQueryService;
import com.ses.service.certificationlearninggap.CertificationLearningGapSelfService;
import com.ses.service.certificationlearninggap.CertificationLearningGapTrainingApprovalService;
import com.ses.service.security.impl.FileScopeValidationService;
import com.ses.service.training.TrainingCourseMasterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NF03: 資格証憑IDOR。専用APIのロール境界と、汎用document download迂回の否定。 */
@ExtendWith(MockitoExtension.class)
class CertificationEvidenceIdorMockMvcTest {

    @Mock private CertificationEvidenceAccessService evidenceAccessService;
    @Mock private DocumentService documentService;
    @Mock private FileScopeValidationService fileScopeValidationService;
    @Mock private CertificationLearningGapSelfService selfService;
    @Mock private CertificationLearningGapQueryService queryService;
    @Mock private CertificationLearningGapTrainingApprovalService trainingApprovalService;
    @Mock private CertificationLearningGapAiService aiService;
    @Mock private CertificationMasterService certificationMasterService;
    @Mock private EngineerCertificationService engineerCertificationService;
    @Mock private TrainingCourseMasterService trainingCourseMasterService;
    @Mock private com.ses.service.DocumentExportService documentExportService;

    private MockMvc myMvc;
    private MockMvc managementMvc;
    private MockMvc documentsMvc;

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "messageSource", new StaticMessageSource());

        myMvc = MockMvcBuilders.standaloneSetup(
                        new MyCertificationLearningGapApiController(selfService, evidenceAccessService))
                .setControllerAdvice(handler).build();
        managementMvc = MockMvcBuilders.standaloneSetup(
                        new CertificationLearningGapApiController(
                                queryService, trainingApprovalService, evidenceAccessService, aiService,
                                certificationMasterService, engineerCertificationService,
                                trainingCourseMasterService))
                .setControllerAdvice(handler).build();
        documentsMvc = MockMvcBuilders.standaloneSetup(
                        new DocumentApiController(documentService, documentExportService,
                                fileScopeValidationService))
                .setControllerAdvice(handler).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(String role, long userId) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setRole(role);
        user.setTenantId("default");
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    @Test
    void 要員Aが他要員Bの証憑をdownloadすると403() throws Exception {
        login("要員", 100L);
        when(evidenceAccessService.downloadForSelf(eq(100L), eq(11L), eq(77L), eq(2)))
                .thenThrow(BusinessException.of(403, "error.forbidden"));

        myMvc.perform(get("/api/my/certification-learning-gap/certifications/11/evidence/77/versions/2/download"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void 要員Aが本人証憑をdownloadすると200() throws Exception {
        login("要員", 100L);
        when(evidenceAccessService.downloadForSelf(eq(100L), eq(11L), eq(77L), eq(2)))
                .thenReturn(new CertificationEvidenceAccessService.EvidenceDownload(
                        77L, 2, "own.pdf", "application/pdf", new ByteArrayInputStream("ok".getBytes())));

        myMvc.perform(get("/api/my/certification-learning-gap/certifications/11/evidence/77/versions/2/download"))
                .andExpect(status().isOk());
    }

    @Test
    void 営業unscopedは管理証憑downloadを403() throws Exception {
        login("営業", 200L);
        when(evidenceAccessService.downloadForManagement(eq(42L), eq(11L), eq(77L), eq(2), any()))
                .thenThrow(BusinessException.of(403, "error.forbidden"));

        managementMvc.perform(get(
                        "/api/certification-learning-gap/42/certifications/11/evidence/77/versions/2/download"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void HRscopedは管理証憑downloadを200() throws Exception {
        login("HR", 300L);
        when(evidenceAccessService.downloadForManagement(eq(42L), eq(11L), eq(77L), eq(2), any()))
                .thenReturn(new CertificationEvidenceAccessService.EvidenceDownload(
                        77L, 2, "hr.pdf", "application/pdf", new ByteArrayInputStream("hr".getBytes())));

        managementMvc.perform(get(
                        "/api/certification-learning-gap/42/certifications/11/evidence/77/versions/2/download"))
                .andExpect(status().isOk());
    }

    @Test
    void 汎用document_downloadは資格証憑認可を迂回できない() throws Exception {
        login("営業", 200L);
        com.ses.dto.document.DocumentDetailDTO detail = com.ses.dto.document.DocumentDetailDTO.builder()
                .id(9100L)
                .documentType("CERTIFICATION_EVIDENCE")
                .versions(List.of(com.ses.dto.document.DocumentVersionDTO.builder()
                        .versionNo(1).originalName("ev.pdf").build()))
                .build();
        when(documentService.getDocumentDetail(9100L)).thenReturn(detail);
        when(documentService.getVersionStorageKey(9100L, 1)).thenReturn("cert-evidence.pdf");
        doThrow(BusinessException.of(403, "error.forbidden"))
                .when(fileScopeValidationService).assertDownloadAllowed("cert-evidence.pdf");

        documentsMvc.perform(get("/api/documents/9100/versions/1/download"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verify(documentService, never()).download(anyLong(), anyInt());
    }
}
