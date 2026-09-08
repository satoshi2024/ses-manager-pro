package com.ses.controller.api;

import com.ses.service.certification.CertificationMasterService;
import com.ses.service.certification.EngineerCertificationService;
import com.ses.service.certificationlearninggap.CertificationEvidenceAccessService;
import com.ses.service.certificationlearninggap.CertificationLearningGapAiService;
import com.ses.service.certificationlearninggap.CertificationLearningGapQueryService;
import com.ses.service.certificationlearninggap.CertificationLearningGapTrainingApprovalService;
import com.ses.service.skillgap.AiLearningCandidateService;
import com.ses.service.training.TrainingCourseMasterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NF-03のAI候補accept/reject HTTP入口がcandidateを再判断serviceへ渡すことを固定する。 */
@ExtendWith(MockitoExtension.class)
class CertificationLearningGapCandidateMockMvcTest {

    @Mock private CertificationLearningGapQueryService queryService;
    @Mock private CertificationLearningGapTrainingApprovalService trainingApprovalService;
    @Mock private CertificationEvidenceAccessService evidenceAccessService;
    @Mock private CertificationLearningGapAiService aiService;
    @Mock private CertificationMasterService certificationMasterService;
    @Mock private EngineerCertificationService engineerCertificationService;
    @Mock private TrainingCourseMasterService trainingCourseMasterService;
    @Mock private AiLearningCandidateService candidateService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CertificationLearningGapApiController controller = new CertificationLearningGapApiController(
                queryService, trainingApprovalService, evidenceAccessService, aiService,
                certificationMasterService, engineerCertificationService, trainingCourseMasterService);
        ReflectionTestUtils.setField(controller, "aiLearningCandidateService", candidateService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("100", "n", "ROLE_マネージャー"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptはURLのengineerIdを期待scopeとしてserviceへ渡す() throws Exception {
        mockMvc.perform(post("/api/certification-learning-gap/12/ai-candidates/55/accept")
                        .contentType("application/json")
                        .content("{\"reason\":\"人が確認した\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(candidateService).acceptCandidate(eq(55L), eq(12L), eq(100L), eq("人が確認した"));
    }

    @Test
    void rejectもreasonを必須境界へ渡す() throws Exception {
        mockMvc.perform(post("/api/certification-learning-gap/12/ai-candidates/56/reject")
                        .contentType("application/json")
                        .content("{\"reason\":\"対象外\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(candidateService).rejectCandidate(eq(56L), eq(12L), eq(100L), eq("対象外"));
    }
}
