package com.ses.service.certificationlearninggap;

import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import com.ses.entity.Certification;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.CertificationMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.LearningPlanMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingEnrollmentMapper;
import com.ses.service.EngineerService;
import com.ses.service.SkillGapService;
import com.ses.service.certification.CertificationNumberCryptoService;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificationLearningGapQueryServiceImplTest {

    @Mock private EngineerService engineerService;
    @Mock private DataScopeService dataScopeService;
    @Mock private OrganizationScopeService organizationScopeService;
    @Mock private AuthorizationService authorizationService;
    @Mock private EngineerCertificationMapper certificationRecordMapper;
    @Mock private CertificationMapper certificationMapper;
    @Mock private LearningPlanMapper learningPlanMapper;
    @Mock private TrainingEnrollmentMapper enrollmentMapper;
    @Mock private TrainingCourseMapper courseMapper;
    @Mock private LifecycleCaseMapper lifecycleCaseMapper;
    @Mock private SkillGapService skillGapService;
    @Mock private CertificationNumberCryptoService numberCryptoService;
    @Mock private DocumentLinkMapper documentLinkMapper;
    @Mock private DocumentVersionMapper documentVersionMapper;

    private CertificationLearningGapQueryServiceImpl service;
    private final Authentication authentication = new TestingAuthenticationToken("100", "n", "ROLE_HR");

    @BeforeEach
    void setUp() {
        service = new CertificationLearningGapQueryServiceImpl(engineerService, dataScopeService,
                organizationScopeService, authorizationService, certificationRecordMapper, certificationMapper,
                learningPlanMapper, enrollmentMapper, courseMapper, lifecycleCaseMapper, skillGapService,
                numberCryptoService, documentLinkMapper, documentVersionMapper);
        when(dataScopeService.isScoped()).thenReturn(false);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(certificationRecordMapper.selectList(any())).thenReturn(List.of());
        when(learningPlanMapper.selectList(any())).thenReturn(List.of());
        when(enrollmentMapper.selectList(any())).thenReturn(List.of());
        when(lifecycleCaseMapper.selectList(any())).thenReturn(List.of());
        when(documentLinkMapper.selectList(any())).thenReturn(List.of());
        when(authorizationService.isAllowed(any(), any())).thenReturn(false);
    }

    @Test
    void managerはorgとDataScopeの積集合だけを可視化する() {
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of(1L, 2L));
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedEngineerIds(LocalDate.of(2026, 8, 28))).thenReturn(Set.of(2L, 3L));
        when(organizationScopeService.intersectWithDataScope(anySet(), eq(Set.of(1L, 2L)))).thenReturn(Set.of(2L));

        assertEquals(Set.of(2L), service.visibleEngineerIds(LocalDate.of(2026, 8, 28)));
    }

    @Test
    void list_detail_count_exportは同じID母集団で番号policyだけが異なる() {
        Engineer first = engineer(1L, "対象 一郎");
        Engineer second = engineer(2L, "対象 二郎");
        when(engineerService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(first, second));

        EngineerCertification record = new EngineerCertification();
        record.setId(11L);
        record.setEngineerId(1L);
        record.setCertificationId(21L);
        record.setTenantId("default");
        record.setRecordState("ACTIVE");
        record.setCurrentFlag(1);
        record.setAcquiredOn(LocalDate.of(2026, 1, 1));
        record.setExpiresOn(LocalDate.of(2027, 1, 1));
        record.setCertificateNumberEncrypted(new byte[]{1, 2, 3});
        record.setCertificateNumberMasked("********1234");
        record.setCertificateNumberKeyVersion("v1");
        record.setCertificateNumberCipherFormat("CNF1");
        when(certificationRecordMapper.selectList(any())).thenReturn(List.of(record));
        Certification master = new Certification();
        master.setId(21L);
        master.setDisplayName("基本資格");
        when(certificationMapper.selectBatchIds(anySet())).thenReturn(List.of(master));
        when(authorizationService.isAllowed(authentication, "certification.pii.view")).thenReturn(true);
        when(numberCryptoService.decrypt(eq("default"), eq(11L), any(), eq("v1"), eq("CNF1")))
                .thenReturn("CERT-1234");

        CertificationLearningGapFilter filter = new CertificationLearningGapFilter(null, null, null, null, null,
                LocalDate.of(2026, 8, 28), null, SkillGapService.DemandSource.COMBINED);
        List<Long> listIds = service.page(filter, 1, 0, authentication).getRecords().stream()
                .map(CertificationLearningGapRow::engineerId).toList();
        List<Long> exportIds = service.export(filter, authentication).stream()
                .map(CertificationLearningGapRow::engineerId).toList();
        assertEquals(listIds, exportIds);
        assertEquals(2, service.count(filter, authentication));
        assertEquals("CERT-1234", service.detail(1L, filter, authentication).certifications().get(0).certificateNumber());
        assertFalse(service.export(filter, authentication).get(0).certifications().get(0).canViewFullNumber());
        assertNull(service.export(filter, authentication).get(0).certifications().get(0).certificateNumber());
        assertEquals(10, service.page(filter, 1, 0, authentication).getSize(), "size=0はsafePageの既定値へ補正");
    }

    @Test
    void scope外detailはDB取得に依存せず404相当の空結果になる() {
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of(1L));
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedEngineerIds(any(LocalDate.class))).thenReturn(Set.of(1L));
        when(organizationScopeService.intersectWithDataScope(any(), any())).thenReturn(Set.of(1L));

        org.junit.jupiter.api.Assertions.assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.detail(2L, new CertificationLearningGapFilter(null, null, null, null, null,
                        LocalDate.now(), null, null), authentication));
    }

    @Test
    void skillGapにはSELFやMANAGERの評価を混入させない() {
        when(engineerService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(engineer(1L, "対象 一郎")));
        when(skillGapService.calculate(any())).thenReturn(new com.ses.dto.skillgap.SkillGapResult(
                SkillGapService.STATUS_OK, null, LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 28), 1L, 9L, SkillGapService.DemandSource.PROJECT, List.of(), List.of(), 55L));
        CertificationLearningGapFilter filter = new CertificationLearningGapFilter(null, null, null, null, null,
                LocalDate.of(2026, 8, 28), 9L, SkillGapService.DemandSource.PROJECT);

        CertificationLearningGapRow row = service.page(filter, 1, 10, authentication).getRecords().get(0);
        assertTrue(row.skillGaps().isEmpty());
        assertEquals(SkillGapService.STATUS_OK, row.gapStatus());
    }

    private Engineer engineer(Long id, String name) {
        Engineer engineer = new Engineer();
        engineer.setId(id);
        engineer.setFullName(name);
        engineer.setStatus("稼動中");
        return engineer;
    }
}
