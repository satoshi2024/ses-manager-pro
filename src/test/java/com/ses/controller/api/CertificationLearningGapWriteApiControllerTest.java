package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.certification.CertificationLifecycleActionView;
import com.ses.dto.certificationlearninggap.TrainingCourseMasterView;
import com.ses.entity.Certification;
import com.ses.entity.EngineerCertification;
import com.ses.entity.TrainingCourse;
import com.ses.service.certification.CertificationMasterService;
import com.ses.service.certification.EngineerCertificationService;
import com.ses.service.certificationlearninggap.CertificationEvidenceAccessService;
import com.ses.service.certificationlearninggap.CertificationLearningGapAiService;
import com.ses.service.certificationlearninggap.CertificationLearningGapQueryService;
import com.ses.service.certificationlearninggap.CertificationLearningGapTrainingApprovalService;
import com.ses.service.training.TrainingCourseMasterService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CertificationLearningGapWriteApiControllerTest {

    private final CertificationMasterService masterService = mock(CertificationMasterService.class);
    private final EngineerCertificationService certificationService = mock(EngineerCertificationService.class);
    private final TrainingCourseMasterService courseService = mock(TrainingCourseMasterService.class);
    private final CertificationLearningGapApiController controller = new CertificationLearningGapApiController(
            mock(CertificationLearningGapQueryService.class), mock(CertificationLearningGapTrainingApprovalService.class),
            mock(CertificationEvidenceAccessService.class), mock(CertificationLearningGapAiService.class),
            masterService, certificationService, courseService);

    @Test
    void masterとcourseのwriteは既存serviceへ委譲する() {
        Certification master = new Certification(); master.setId(1L); master.setDisplayName("FE");
        TrainingCourse course = new TrainingCourse(); course.setId(2L); course.setName("AWS");
        when(masterService.createMaster(any(Certification.class), any())).thenReturn(master);
        when(courseService.create(any(TrainingCourseMasterService.TrainingCourseCommand.class), any())).thenReturn(course);

        ApiResult<Certification> masterResult = controller.createCertificationMaster(
                new CertificationLearningGapApiController.CertificationMasterRequest(
                        "default", "FE", "IPA", "FE", "NONE", null, 1, 1));
        ApiResult<TrainingCourse> courseResult = controller.createTrainingCourse(
                new CertificationLearningGapApiController.TrainingCourseMasterRequest(
                        "default", "provider", "AWS", "desc", java.math.BigDecimal.TEN, 3, 10, 1, null, List.of(5L)));

        assertEquals(200, masterResult.getCode());
        assertEquals(1L, masterResult.getData().getId());
        assertEquals(200, courseResult.getCode());
        assertEquals(2L, courseResult.getData().getId());
        verify(masterService).createMaster(any(Certification.class), any());
        verify(courseService).create(any(TrainingCourseMasterService.TrainingCourseCommand.class), any());
    }

    @Test
    void verifyはevidence三点とversionをserviceへ渡す() {
        EngineerCertification record = new EngineerCertification();
        record.setId(10L); record.setEngineerId(20L); record.setCertificationId(30L);
        record.setRecordState("ACTIVE"); record.setVersion(2);
        when(certificationService.verify(10L, 1, null, 40L, 41L, "hash")).thenReturn(record);

        ApiResult<CertificationLifecycleActionView> result = controller.verifyCertification(10L,
                new CertificationLearningGapApiController.CertificationVerificationCommand(1, 40L, 41L, "hash"));

        assertEquals(200, result.getCode());
        assertEquals("ACTIVE", result.getData().recordState());
        assertEquals(2, result.getData().version());
        verify(certificationService).verify(eq(10L), eq(1), eq(null), eq(40L), eq(41L), eq("hash"));
    }

    @Test
    void course一覧の管理endpointはviewを返す() {
        when(courseService.list(true)).thenReturn(List.of(new TrainingCourseMasterView(2L, "default", "p", "n",
                null, java.math.BigDecimal.ONE, 1, 1, 1, 0, List.of())));
        assertEquals(1, controller.trainingCourses(true).getData().size());
    }
}
