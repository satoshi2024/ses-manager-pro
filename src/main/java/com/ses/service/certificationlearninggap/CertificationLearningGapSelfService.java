package com.ses.service.certificationlearninggap;

import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.dto.certificationlearninggap.CertificationSelfDashboard;
import com.ses.dto.certificationlearninggap.CertificationSelfView;
import com.ses.dto.certificationlearninggap.LearningPlanSelfView;
import com.ses.entity.EngineerCertification;
import com.ses.entity.LearningPlan;
import com.ses.entity.Certification;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingEnrollment;
import com.ses.service.training.TrainingPlanService;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** account-linkで解決した本人engineerだけを扱うA2 application service。 */
public interface CertificationLearningGapSelfService {

    CertificationSelfDashboard dashboard(Long actorUserId);

    List<CertificationSelfView> certifications(Long actorUserId);

    CertificationSelfView certification(Long actorUserId, Long recordId);

    List<Certification> availableCertificationMasters();

    List<TrainingCourse> availableTrainingCourses();

    EngineerCertificationViewDto applyCertification(Long actorUserId, Long ignoredEngineerId, Long certificationId,
                                                     LocalDate acquiredOn, LocalDate expiresOn,
                                                     String certificateNumberPlaintext);

    CertificationEvidenceUpload uploadEvidence(Long actorUserId, Long recordId, MultipartFile file);

    EngineerCertification withdrawCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                                String reason);

    EngineerCertification correctCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                               LocalDate acquiredOn, LocalDate expiresOn, String reason);

    EngineerCertificationViewDto resubmitCertification(Long actorUserId, Long recordId,
                                                       String certificateNumberPlaintext);

    List<LearningPlanSelfView> learningPlans(Long actorUserId);

    LearningPlanSelfView learningPlan(Long actorUserId, Long planId);

    LearningPlan createPlan(Long actorUserId, LearningPlan draft);

    LearningPlan updatePlan(Long actorUserId, Long planId, Integer expectedVersion, LearningPlan draft);

    LearningPlan submitPlan(Long actorUserId, Long planId, Integer expectedVersion, String zeroCostReason);

    LearningPlan withdrawPlan(Long actorUserId, Long planId, Integer expectedVersion, String reason);

    LearningPlanSelfView resubmitPlan(Long actorUserId, Long planId);

    TrainingEnrollment enroll(Long actorUserId, Long planId, Long courseId);

    TrainingEnrollment startEnrollment(Long actorUserId, Long enrollmentId, Integer expectedVersion);

    TrainingEnrollment completeEnrollment(Long actorUserId, Long enrollmentId, Integer expectedVersion,
                                           LocalDate completedOn, BigDecimal score);

    TrainingEnrollment cancelEnrollment(Long actorUserId, Long enrollmentId, Integer expectedVersion, String reason);

    record CertificationEvidenceUpload(Long recordId, Long documentId, Long documentVersionId,
                                       Integer versionNo, String originalName, String sha256, String scanStatus) {
    }
}
