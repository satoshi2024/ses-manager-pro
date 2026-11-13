package com.ses.controller.api;

import com.ses.common.exception.GlobalExceptionHandler;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.CertificationContinuityGroupMapper;
import com.ses.mapper.CertificationEventMapper;
import com.ses.mapper.CertificationMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LearningPlanMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingEnrollmentMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.certification.CertificationNumberCryptoService;
import com.ses.service.certification.EngineerCertificationServiceImpl;
import com.ses.service.certificationlearninggap.CertificationEvidenceAccessService;
import com.ses.service.certificationlearninggap.CertificationLearningGapSelfServiceImpl;
import com.ses.service.training.TrainingPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 資格番号の子スイッチ（nf03-certification-pii）がOFFのとき、本人APIの申請・再申請で資格番号を送ると
 * controller → 本人service → 資格service を通って HTTP 400 の {@code ApiResult} になることを確認する（DB不要）。
 */
class MyCertificationNumberEntryDisabledApiTest {

    private final EngineerAccountLinkService accountLinkService = mock(EngineerAccountLinkService.class);
    private final EngineerCertificationMapper recordMapper = mock(EngineerCertificationMapper.class);
    private final CertificationMapper certificationMapper = mock(CertificationMapper.class);
    private final EngineerMapper engineerMapper = mock(EngineerMapper.class);
    private final CertificationNumberCryptoService cryptoService = mock(CertificationNumberCryptoService.class);
    private final CertificationEventMapper eventMapper = mock(CertificationEventMapper.class);
    private final CertificationContinuityGroupMapper continuityGroupMapper =
            mock(CertificationContinuityGroupMapper.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        Clock clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        EngineerCertificationServiceImpl certificationService = new EngineerCertificationServiceImpl(recordMapper,
                certificationMapper, engineerMapper, cryptoService, eventMapper, null, clock, continuityGroupMapper);
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.getNf03().setEnabled(true);
        properties.getNf03CertificationPii().setEnabled(false);
        ReflectionTestUtils.setField(certificationService, "featureSwitches", new FeatureSwitches(properties));

        CertificationLearningGapSelfServiceImpl selfService = new CertificationLearningGapSelfServiceImpl(
                accountLinkService, certificationService, recordMapper, certificationMapper,
                mock(LearningPlanMapper.class), mock(TrainingEnrollmentMapper.class), mock(TrainingCourseMapper.class),
                mock(DocumentService.class), mock(DocumentLinkMapper.class), mock(DocumentVersionMapper.class),
                mock(TrainingPlanService.class), clock);
        when(accountLinkService.findEngineerIdByUserId(100L)).thenReturn(20L);

        mockMvc = MockMvcBuilders.standaloneSetup(new MyCertificationLearningGapApiController(selfService,
                        mock(CertificationEvidenceAccessService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("100", "n", "ROLE_要員"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 資格番号付きの申請はHTTP400のApiResultになり資格recordを作らない() throws Exception {
        mockMvc.perform(post("/api/my/certification-learning-gap/certifications")
                        .contentType("application/json")
                        .content("{\"certificationId\":2,\"acquiredOn\":\"2026-10-01\",\"certificateNumber\":\"CERT-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(recordMapper, never()).insert(any(EngineerCertification.class));
        verifyNoInteractions(cryptoService, engineerMapper, certificationMapper, continuityGroupMapper, eventMapper);
    }

    @Test
    void 資格番号付きの再申請はHTTP400のApiResultになり元recordを消費しない() throws Exception {
        EngineerCertification rejected = new EngineerCertification();
        rejected.setId(11L);
        rejected.setTenantId("default");
        rejected.setEngineerId(20L);
        rejected.setRecordState("REJECTED");
        rejected.setVersion(3);
        when(recordMapper.selectOne(any())).thenReturn(rejected);

        mockMvc.perform(post("/api/my/certification-learning-gap/certifications/11/resubmit")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":3,\"certificateNumber\":\"CERT-2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(recordMapper, never()).selectByTenantIdForUpdate(any(), any());
        verify(recordMapper, never()).insert(any(EngineerCertification.class));
        verifyNoInteractions(cryptoService, eventMapper, continuityGroupMapper);
    }
}
