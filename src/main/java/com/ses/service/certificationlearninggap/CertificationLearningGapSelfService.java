package com.ses.service.certificationlearninggap;

import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.dto.certification.CertificationLifecycleActionView;
import com.ses.dto.certificationlearninggap.CertificationSelfDashboard;
import com.ses.dto.certificationlearninggap.CertificationSelfView;
import com.ses.dto.certificationlearninggap.LearningPlanSelfView;
import com.ses.dto.certificationlearninggap.TrainingEnrollmentSelfView;
import com.ses.dto.certification.CertificationMasterView;
import com.ses.dto.certificationlearninggap.TrainingCourseCatalogView;
import com.ses.entity.LearningPlan;
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

    List<CertificationMasterView> availableCertificationMasters();

    List<TrainingCourseCatalogView> availableTrainingCourses();

    EngineerCertificationViewDto applyCertification(Long actorUserId, Long ignoredEngineerId, Long certificationId,
                                                     LocalDate acquiredOn, LocalDate expiresOn,
                                                     String certificateNumberPlaintext);

    CertificationEvidenceUpload uploadEvidence(Long actorUserId, Long recordId, MultipartFile file);

    CertificationLifecycleActionView withdrawCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                                            String reason);

    CertificationLifecycleActionView correctCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                                          LocalDate acquiredOn, LocalDate expiresOn, String reason);

    EngineerCertificationViewDto resubmitCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                                       String certificateNumberPlaintext);

    List<LearningPlanSelfView> learningPlans(Long actorUserId);

    LearningPlanSelfView learningPlan(Long actorUserId, Long planId);

    LearningPlan createPlan(Long actorUserId, LearningPlan draft);

    LearningPlan updatePlan(Long actorUserId, Long planId, Integer expectedVersion, LearningPlan draft);

    LearningPlan submitPlan(Long actorUserId, Long planId, Integer expectedVersion, String zeroCostReason);

    LearningPlan withdrawPlan(Long actorUserId, Long planId, Integer expectedVersion, String reason);

    LearningPlanSelfView createPlanView(Long actorUserId, LearningPlan draft);
    LearningPlanSelfView updatePlanView(Long actorUserId, Long planId, Integer expectedVersion, LearningPlan draft);
    LearningPlanSelfView submitPlanView(Long actorUserId, Long planId, Integer expectedVersion, String zeroCostReason);
    LearningPlanSelfView withdrawPlanView(Long actorUserId, Long planId, Integer expectedVersion, String reason);

    LearningPlanSelfView resubmitPlan(Long actorUserId, Long planId, Integer expectedVersion);

    TrainingEnrollment enroll(Long actorUserId, Long planId, Integer expectedVersion, Long courseId);

    TrainingEnrollment startEnrollment(Long actorUserId, Long enrollmentId, Integer expectedVersion);

    TrainingEnrollment completeEnrollment(Long actorUserId, Long enrollmentId, Integer expectedVersion,
                                           LocalDate completedOn, BigDecimal score);

    TrainingEnrollment cancelEnrollment(Long actorUserId, Long enrollmentId, Integer expectedVersion, String reason);

    TrainingEnrollmentSelfView enrollView(Long actorUserId, Long planId, Integer expectedVersion, Long courseId);
    TrainingEnrollmentSelfView startEnrollmentView(Long actorUserId, Long enrollmentId, Integer expectedVersion);
    TrainingEnrollmentSelfView completeEnrollmentView(Long actorUserId, Long enrollmentId, Integer expectedVersion,
                                                       LocalDate completedOn, BigDecimal score);
    TrainingEnrollmentSelfView cancelEnrollmentView(Long actorUserId, Long enrollmentId, Integer expectedVersion,
                                                     String reason);

    record CertificationEvidenceUpload(Long recordId, Long documentId, Long documentVersionId,
                                       Integer versionNo, String originalName, String sha256, String scanStatus) {
    }
}
