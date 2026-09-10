package com.ses.controller.api;

import com.ses.dto.certification.CertificationLifecycleActionView;
import com.ses.dto.certification.CertificationMasterView;
import com.ses.dto.certificationlearninggap.TrainingCourseCatalogView;
import com.ses.service.certificationlearninggap.CertificationEvidenceAccessService;
import com.ses.service.certificationlearninggap.CertificationLearningGapSelfService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 要員本人向け状態変更APIは許可リストDTOだけを返し、内部情報を漏らさない。 */
@ExtendWith(MockitoExtension.class)
class MyCertificationLearningGapApiControllerMockMvcTest {

    @Mock
    private CertificationLearningGapSelfService selfService;
    @Mock
    private CertificationEvidenceAccessService evidenceAccessService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MyCertificationLearningGapApiController(selfService,
                evidenceAccessService)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("100", "n", "ROLE_要員"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 本人資格訂正レスポンスは許可リストDTOだけを返す() throws Exception {
        CertificationLifecycleActionView view = new CertificationLifecycleActionView(12L, "DRAFT", 1,
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 7, 31), 2, 7, "****1234");
        when(selfService.correctCertification(eq(100L), eq(12L), eq(7), eq(LocalDate.of(2026, 8, 1)),
                eq(LocalDate.of(2027, 7, 31)), eq("訂正"))).thenReturn(view);

        mockMvc.perform(post("/api/my/certification-learning-gap/certifications/12/correct")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":7,\"acquiredOn\":\"2026-08-01\","
                                + "\"expiresOn\":\"2027-07-31\",\"reason\":\"訂正\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(12))
                .andExpect(jsonPath("$.data.recordState").value("DRAFT"))
                .andExpect(jsonPath("$.data.currentFlag").value(1))
                .andExpect(jsonPath("$.data.acquiredOn[0]").value(2026))
                .andExpect(jsonPath("$.data.acquiredOn[1]").value(8))
                .andExpect(jsonPath("$.data.acquiredOn[2]").value(1))
                .andExpect(jsonPath("$.data.expiresOn[0]").value(2027))
                .andExpect(jsonPath("$.data.expiresOn[1]").value(7))
                .andExpect(jsonPath("$.data.expiresOn[2]").value(31))
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.version").value(7))
                .andExpect(jsonPath("$.data.certificateNumberMasked").value("****1234"))
                .andExpect(jsonPath("$.data.encryptedCertificateNumber").doesNotExist())
                .andExpect(jsonPath("$.data.certificateNumberCipher").doesNotExist())
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").doesNotExist())
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());
    }

    @Test
    void 本人資格取消レスポンスもmasked番号とversion以外の内部情報を返さない() throws Exception {
        CertificationLifecycleActionView view = new CertificationLifecycleActionView(12L, "CANCELLED", 0,
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 7, 31), 3, 8, "****1234");
        when(selfService.withdrawCertification(eq(100L), eq(12L), eq(8), eq("取消"))).thenReturn(view);

        mockMvc.perform(post("/api/my/certification-learning-gap/certifications/12/withdraw")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":8,\"reason\":\"取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(12))
                .andExpect(jsonPath("$.data.recordState").value("CANCELLED"))
                .andExpect(jsonPath("$.data.currentFlag").value(0))
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.version").value(8))
                .andExpect(jsonPath("$.data.certificateNumberMasked").value("****1234"))
                .andExpect(jsonPath("$.data.certificateNumberEncrypted").doesNotExist())
                .andExpect(jsonPath("$.data.certificateNumberKeyVersion").doesNotExist())
                .andExpect(jsonPath("$.data.certificateNumberCipherFormat").doesNotExist())
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy").doesNotExist())
                .andExpect(jsonPath("$.data.updatedBy").doesNotExist());
    }

    @Test
    void 本人catalogは安全projectionだけを返す() throws Exception {
        when(selfService.availableCertificationMasters()).thenReturn(List.of(
                new CertificationMasterView(21L, "AWS SAA", "Amazon", "aws-saa",
                        "NONE", null, 1, 1, 0)));
        when(selfService.availableTrainingCourses()).thenReturn(List.of(
                new TrainingCourseCatalogView(31L, "社内講座", "AWS基礎", "説明",
                        BigDecimal.TEN, 2, 20, 1, 0)));

        mockMvc.perform(get("/api/my/certification-learning-gap/catalog/certifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(21))
                .andExpect(jsonPath("$.data[0].displayName").value("AWS SAA"))
                .andExpect(jsonPath("$.data[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data[0].identityKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$.data[0].deletedFlag").doesNotExist());

        mockMvc.perform(get("/api/my/certification-learning-gap/catalog/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(31))
                .andExpect(jsonPath("$.data[0].name").value("AWS基礎"))
                .andExpect(jsonPath("$.data[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data[0].identityKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$.data[0].deletedFlag").doesNotExist());
    }
}
