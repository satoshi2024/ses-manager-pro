package com.ses.service.certificationlearninggap;

import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.entity.Certification;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.EngineerCertification;
import com.ses.entity.LearningPlan;
import com.ses.mapper.CertificationMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.LearningPlanMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingEnrollmentMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.certification.EngineerCertificationService;
import com.ses.service.training.TrainingPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationLearningGapSelfServiceImplTest {

    @Mock private EngineerAccountLinkService accountLinkService;
    @Mock private EngineerCertificationService certificationService;
    @Mock private EngineerCertificationMapper certificationMapper;
    @Mock private CertificationMapper certificationMasterMapper;
    @Mock private LearningPlanMapper planMapper;
    @Mock private TrainingEnrollmentMapper enrollmentMapper;
    @Mock private TrainingCourseMapper courseMapper;
    @Mock private DocumentService documentService;
    @Mock private DocumentLinkMapper documentLinkMapper;
    @Mock private DocumentVersionMapper documentVersionMapper;
    @Mock private TrainingPlanService trainingPlanService;

    private CertificationLearningGapSelfServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CertificationLearningGapSelfServiceImpl(accountLinkService, certificationService,
                certificationMapper, certificationMasterMapper, planMapper, enrollmentMapper, courseMapper,
                documentService, documentLinkMapper, documentVersionMapper, trainingPlanService,
                Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneId.of("Asia/Tokyo")));
        when(accountLinkService.findEngineerIdByUserId(100L)).thenReturn(42L);
    }

    @Test
    void 申請は入力engineerIdを無視してaccountLinkの本人IDを使う() {
        EngineerCertificationViewDto result = EngineerCertificationViewDto.builder().id(9L).build();
        when(certificationService.submitApplication(eq(42L), eq(7L), eq(LocalDate.of(2026, 8, 1)),
                eq(LocalDate.of(2027, 7, 31)), eq("N-1"), eq(100L), eq(false))).thenReturn(result);

        assertEquals(result, service.applyCertification(100L, 999L, 7L, LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31), "N-1"));
        verify(certificationService).submitApplication(42L, 7L, LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 7, 31), "N-1", 100L, false);
    }

    @Test
    void 他人の資格recordは本人APIから取得できない() {
        EngineerCertification record = new EngineerCertification();
        record.setId(11L);
        record.setEngineerId(43L);
        when(certificationMapper.selectById(11L)).thenReturn(record);

        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.certification(100L, 11L));
    }

    @Test
    void 証憑登録はtypedCertificationRecordLinkとCLEAN版metadataを返す() throws Exception {
        EngineerCertification record = new EngineerCertification();
        record.setId(11L);
        record.setEngineerId(42L);
        when(certificationMapper.selectById(11L)).thenReturn(record);
        Document document = new Document();
        document.setId(77L);
        when(documentService.registerReceived(any(), any())).thenReturn(document);
        DocumentVersion version = new DocumentVersion();
        version.setId(88L);
        version.setDocumentId(77L);
        version.setVersionNo(1);
        version.setOriginalName("evidence.pdf");
        version.setSha256("abc");
        version.setScanStatus("CLEAN");
        when(documentVersionMapper.findLatestByDocumentId(77L)).thenReturn(version);

        var result = service.uploadEvidence(100L, 11L,
                new MockMultipartFile("file", "evidence.pdf", "application/pdf", "pdf".getBytes()));

        assertEquals(77L, result.documentId());
        assertEquals("CLEAN", result.scanStatus());
        ArgumentCaptor<com.ses.dto.document.DocumentRegisterRequest> captor =
                ArgumentCaptor.forClass(com.ses.dto.document.DocumentRegisterRequest.class);
        verify(documentService).registerReceived(captor.capture(), any());
        assertEquals("CERTIFICATION_EVIDENCE", captor.getValue().getDocumentType());
        assertEquals("CERTIFICATION_RECORD", captor.getValue().getTargetType());
        assertEquals(11L, captor.getValue().getTargetId());
    }

    @Test
    void 学習計画作成も入力engineerIdを上書きする() {
        LearningPlan incoming = new LearningPlan();
        incoming.setEngineerId(999L);
        incoming.setTitle("資格学習");
        incoming.setAttainmentCriteria("試験合格");
        incoming.setPlannedCostJpy(java.math.BigDecimal.ZERO);
        LearningPlan saved = new LearningPlan();
        saved.setEngineerId(42L);
        when(trainingPlanService.createDraft(any(LearningPlan.class), eq(100L))).thenReturn(saved);

        assertEquals(saved, service.createPlan(100L, incoming));
        ArgumentCaptor<LearningPlan> captor = ArgumentCaptor.forClass(LearningPlan.class);
        verify(trainingPlanService).createDraft(captor.capture(), eq(100L));
        assertEquals(42L, captor.getValue().getEngineerId());
    }
}
