package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.dto.certificationlearninggap.CertificationEvidenceView;
import com.ses.dto.certificationlearninggap.CertificationSelfDashboard;
import com.ses.dto.certificationlearninggap.CertificationSelfView;
import com.ses.dto.certificationlearninggap.LearningPlanSelfView;
import com.ses.dto.certificationlearninggap.TrainingEnrollmentSelfView;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.entity.Certification;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.EngineerCertification;
import com.ses.entity.LearningPlan;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingEnrollment;
import com.ses.mapper.CertificationMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.LearningPlanMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingEnrollmentMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.certification.CertificationRecordStates;
import com.ses.service.certification.EngineerCertificationService;
import com.ses.service.training.TrainingPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 本人ポータルのscope・証憑・状態遷移を一箇所で検証する。 */
@Service
@RequiredArgsConstructor
public class CertificationLearningGapSelfServiceImpl implements CertificationLearningGapSelfService {

    private final EngineerAccountLinkService accountLinkService;
    private final EngineerCertificationService certificationService;
    private final EngineerCertificationMapper certificationMapper;
    private final CertificationMapper certificationMasterMapper;
    private final LearningPlanMapper planMapper;
    private final TrainingEnrollmentMapper enrollmentMapper;
    private final TrainingCourseMapper courseMapper;
    private final DocumentService documentService;
    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final TrainingPlanService trainingPlanService;
    private final Clock clock;

    @Override
    public CertificationSelfDashboard dashboard(Long actorUserId) {
        return new CertificationSelfDashboard(certifications(actorUserId), learningPlans(actorUserId));
    }

    @Override
    public List<CertificationSelfView> certifications(Long actorUserId) {
        Long engineerId = ownEngineerId(actorUserId);
        List<EngineerCertification> records = certificationMapper.selectList(new LambdaQueryWrapper<EngineerCertification>()
                .eq(EngineerCertification::getEngineerId, engineerId)
                .orderByDesc(EngineerCertification::getAcquiredOn)
                .orderByDesc(EngineerCertification::getId));
        List<Long> certificationIds = records.stream().map(EngineerCertification::getCertificationId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Certification> masters = certificationIds.isEmpty() ? Map.of()
                : certificationMasterMapper.selectBatchIds(certificationIds).stream()
                .collect(java.util.stream.Collectors.toMap(Certification::getId, item -> item, (a, b) -> a));
        return records.stream().map(record -> toCertificationView(record, masters.get(record.getCertificationId()))).toList();
    }

    @Override
    public CertificationSelfView certification(Long actorUserId, Long recordId) {
        EngineerCertification record = ownCertification(actorUserId, recordId);
        Certification master = record.getCertificationId() == null ? null : certificationMasterMapper.selectById(record.getCertificationId());
        return toCertificationView(record, master);
    }

    @Override
    public List<Certification> availableCertificationMasters() {
        return certificationMasterMapper.selectList(new LambdaQueryWrapper<Certification>()
                .eq(Certification::getActiveFlag, 1)
                .orderByAsc(Certification::getDisplayName).orderByAsc(Certification::getId));
    }

    @Override
    public List<TrainingCourse> availableTrainingCourses() {
        return courseMapper.selectList(new LambdaQueryWrapper<TrainingCourse>()
                .eq(TrainingCourse::getActiveFlag, 1)
                .orderByAsc(TrainingCourse::getName).orderByAsc(TrainingCourse::getId));
    }

    @Override
    public EngineerCertificationViewDto applyCertification(Long actorUserId, Long ignoredEngineerId, Long certificationId,
                                                           LocalDate acquiredOn, LocalDate expiresOn,
                                                           String certificateNumberPlaintext) {
        Long engineerId = ownEngineerId(actorUserId);
        // ignoredEngineerIdは互換入力として受けるが、server-sideの本人IDを必ず正本にする。
        return certificationService.submitApplication(engineerId, certificationId, acquiredOn, expiresOn,
                certificateNumberPlaintext, actorUserId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CertificationEvidenceUpload uploadEvidence(Long actorUserId, Long recordId, MultipartFile file) {
        EngineerCertification record = ownCertification(actorUserId, recordId);
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(400, "error.file.empty");
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "evidence";
        try {
            DocumentRegisterRequest request = DocumentRegisterRequest.builder()
                    .documentType("CERTIFICATION_EVIDENCE")
                    .title(originalName)
                    .originalName(originalName)
                    .contentType(StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/octet-stream")
                    .sourceType("RECEIVED")
                    .direction("INCOMING")
                    .counterpartyType("INTERNAL")
                    .transactionDate(LocalDate.now(clock))
                    .businessKey("CERTIFICATION_EVIDENCE:" + record.getId() + ":" + UUID.randomUUID())
                    .versionDiscriminator("v1")
                    .targetType("CERTIFICATION_RECORD")
                    .targetId(record.getId())
                    .createdBy(actorUserId)
                    .build();
            com.ses.entity.Document document = documentService.registerReceived(request, file.getInputStream());
            DocumentVersion version = documentVersionMapper.findLatestByDocumentId(document.getId());
            if (version == null || !"CLEAN".equals(version.getScanStatus())) {
                throw BusinessException.of(400, "error.file.scanRejected");
            }
            return new CertificationEvidenceUpload(record.getId(), document.getId(), version.getId(), version.getVersionNo(),
                    version.getOriginalName(), version.getSha256(), version.getScanStatus());
        } catch (IOException e) {
            throw BusinessException.of(400, "error.file.readFailed");
        }
    }

    @Override
    public EngineerCertification withdrawCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                                        String reason) {
        EngineerCertification record = ownCertification(actorUserId, recordId);
        return certificationService.cancel(record.getId(), expectedVersion, actorUserId, reason);
    }

    @Override
    public EngineerCertification correctCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                                       LocalDate acquiredOn, LocalDate expiresOn, String reason) {
        EngineerCertification record = ownCertification(actorUserId, recordId);
        return certificationService.correct(record.getId(), expectedVersion, acquiredOn, expiresOn, actorUserId, reason);
    }

    @Override
    public EngineerCertificationViewDto resubmitCertification(Long actorUserId, Long recordId,
                                                               String certificateNumberPlaintext) {
        EngineerCertification previous = ownCertification(actorUserId, recordId);
        if (!CertificationRecordStates.CANCELLED.equals(previous.getRecordState())
                && !CertificationRecordStates.REJECTED.equals(previous.getRecordState())) {
            throw BusinessException.of(400, "certification.record.invalidTransition");
        }
        return certificationService.submitApplication(previous.getEngineerId(), previous.getCertificationId(),
                previous.getAcquiredOn(), previous.getExpiresOn(), certificateNumberPlaintext, actorUserId, false);
    }

    @Override
    public List<LearningPlanSelfView> learningPlans(Long actorUserId) {
        Long engineerId = ownEngineerId(actorUserId);
        return planMapper.selectList(new LambdaQueryWrapper<LearningPlan>()
                        .eq(LearningPlan::getEngineerId, engineerId)
                        .orderByDesc(LearningPlan::getId))
                .stream().map(this::toPlanView).toList();
    }

    @Override
    public LearningPlanSelfView learningPlan(Long actorUserId, Long planId) {
        return toPlanView(ownPlan(actorUserId, planId));
    }

    @Override
    public LearningPlan createPlan(Long actorUserId, LearningPlan draft) {
        draft = copyPlan(draft);
        draft.setEngineerId(ownEngineerId(actorUserId));
        return trainingPlanService.createDraft(draft, actorUserId);
    }

    @Override
    public LearningPlan updatePlan(Long actorUserId, Long planId, Integer expectedVersion, LearningPlan draft) {
        ownPlan(actorUserId, planId);
        draft = copyPlan(draft);
        draft.setEngineerId(ownEngineerId(actorUserId));
        return trainingPlanService.updateDraft(planId, expectedVersion, draft, actorUserId);
    }

    @Override
    public LearningPlan submitPlan(Long actorUserId, Long planId, Integer expectedVersion, String zeroCostReason) {
        ownPlan(actorUserId, planId);
        return trainingPlanService.submit(planId, expectedVersion, actorUserId, zeroCostReason);
    }

    @Override
    public LearningPlan withdrawPlan(Long actorUserId, Long planId, Integer expectedVersion, String reason) {
        ownPlan(actorUserId, planId);
        return trainingPlanService.cancelPlan(planId, expectedVersion, actorUserId, reason);
    }

    @Override
    public LearningPlanSelfView resubmitPlan(Long actorUserId, Long planId) {
        LearningPlan previous = ownPlan(actorUserId, planId);
        if (!TrainingPlanService.PLAN_REJECTED.equals(previous.getStatus())
                && !TrainingPlanService.PLAN_CANCELLED.equals(previous.getStatus())) {
            throw BusinessException.of(400, "training.plan.invalidTransition");
        }
        LearningPlan draft = copyPlan(previous);
        draft.setId(null);
        draft.setEngineerId(ownEngineerId(actorUserId));
        draft.setStatus(null);
        return toPlanView(trainingPlanService.createDraft(draft, actorUserId));
    }

    @Override
    public TrainingEnrollment enroll(Long actorUserId, Long planId, Long courseId) {
        ownPlan(actorUserId, planId);
        return trainingPlanService.enroll(planId, courseId, actorUserId);
    }

    @Override
    public TrainingEnrollment startEnrollment(Long actorUserId, Long enrollmentId, Integer expectedVersion) {
        ownEnrollment(actorUserId, enrollmentId);
        return trainingPlanService.startEnrollment(enrollmentId, expectedVersion, actorUserId);
    }

    @Override
    public TrainingEnrollment completeEnrollment(Long actorUserId, Long enrollmentId, Integer expectedVersion,
                                                 LocalDate completedOn, java.math.BigDecimal score) {
        ownEnrollment(actorUserId, enrollmentId);
        return trainingPlanService.completeEnrollment(enrollmentId, expectedVersion, completedOn, score, actorUserId);
    }

    @Override
    public TrainingEnrollment cancelEnrollment(Long actorUserId, Long enrollmentId, Integer expectedVersion, String reason) {
        ownEnrollment(actorUserId, enrollmentId);
        return trainingPlanService.cancelEnrollment(enrollmentId, expectedVersion, actorUserId, reason);
    }

    private CertificationSelfView toCertificationView(EngineerCertification record, Certification master) {
        LocalDate asOf = LocalDate.now(clock);
        EngineerCertificationViewDto dto = EngineerCertificationViewDto.builder()
                .id(record.getId())
                .engineerId(record.getEngineerId())
                .certificationId(record.getCertificationId())
                .certificationDisplayName(master == null ? null : master.getDisplayName())
                .acquiredOn(record.getAcquiredOn())
                .expiresOn(record.getExpiresOn())
                .recordState(record.getRecordState())
                .currentFlag(record.getCurrentFlag())
                .certificateNumberMasked(record.getCertificateNumberMasked())
                .canViewFullNumber(false)
                .build();
        List<CertificationEvidenceView> evidences = documentLinkMapper.selectList(new LambdaQueryWrapper<DocumentLink>()
                        .eq(DocumentLink::getTargetType, "CERTIFICATION_RECORD")
                        .eq(DocumentLink::getTargetId, record.getId()))
                .stream().map(DocumentLink::getDocumentId).filter(Objects::nonNull).distinct()
                .map(documentVersionMapper::findLatestByDocumentId)
                .filter(Objects::nonNull)
                .map(version -> new CertificationEvidenceView(version.getDocumentId(), version.getId(), version.getVersionNo(),
                        version.getOriginalName(), version.getSha256(), version.getScanStatus()))
                .toList();
        return new CertificationSelfView(dto, evidences);
    }

    private LearningPlanSelfView toPlanView(LearningPlan plan) {
        Map<Long, TrainingCourse> courses = new LinkedHashMap<>();
        List<TrainingEnrollment> enrollments = enrollmentMapper.selectList(new LambdaQueryWrapper<TrainingEnrollment>()
                .eq(TrainingEnrollment::getPlanId, plan.getId()).orderByDesc(TrainingEnrollment::getId));
        enrollments.stream().map(TrainingEnrollment::getCourseId).filter(Objects::nonNull).distinct()
                .map(courseMapper::selectById).filter(Objects::nonNull).forEach(course -> courses.put(course.getId(), course));
        List<TrainingEnrollmentSelfView> views = enrollments.stream().map(enrollment -> new TrainingEnrollmentSelfView(
                enrollment.getId(), enrollment.getPlanId(), enrollment.getCourseId(),
                courses.containsKey(enrollment.getCourseId()) ? courses.get(enrollment.getCourseId()).getName() : null,
                enrollment.getStatus(), enrollment.getStartedOn(), enrollment.getCompletedOn(), enrollment.getScore(),
                enrollment.getPlannedCostSnapshot(), enrollment.getVersion())).toList();
        return new LearningPlanSelfView(plan, views);
    }

    private EngineerCertification ownCertification(Long actorUserId, Long recordId) {
        Long engineerId = ownEngineerId(actorUserId);
        EngineerCertification record = recordId == null ? null : certificationMapper.selectById(recordId);
        if (record == null || !engineerId.equals(record.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return record;
    }

    private LearningPlan ownPlan(Long actorUserId, Long planId) {
        Long engineerId = ownEngineerId(actorUserId);
        LearningPlan plan = planId == null ? null : planMapper.selectById(planId);
        if (plan == null || !engineerId.equals(plan.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return plan;
    }

    private TrainingEnrollment ownEnrollment(Long actorUserId, Long enrollmentId) {
        Long engineerId = ownEngineerId(actorUserId);
        TrainingEnrollment enrollment = enrollmentId == null ? null : enrollmentMapper.selectById(enrollmentId);
        if (enrollment == null || !engineerId.equals(enrollment.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return enrollment;
    }

    private Long ownEngineerId(Long actorUserId) {
        if (actorUserId == null) {
            throw BusinessException.of(403, "error.my.notLinked");
        }
        Long engineerId = accountLinkService.findEngineerIdByUserId(actorUserId);
        if (engineerId == null) {
            throw BusinessException.of(403, "error.my.notLinked");
        }
        return engineerId;
    }

    private LearningPlan copyPlan(LearningPlan source) {
        if (source == null) {
            throw BusinessException.of(400, "training.plan.invalid");
        }
        LearningPlan copy = new LearningPlan();
        copy.setTenantId(source.getTenantId());
        copy.setEngineerId(source.getEngineerId());
        copy.setTitle(source.getTitle());
        copy.setGoalDescription(source.getGoalDescription());
        copy.setAttainmentCriteria(source.getAttainmentCriteria());
        copy.setPlannedStartOn(source.getPlannedStartOn());
        copy.setPlannedEndOn(source.getPlannedEndOn());
        copy.setPlannedCostJpy(source.getPlannedCostJpy());
        return copy;
    }
}
