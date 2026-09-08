package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.certification.EngineerCertificationViewDto;
import com.ses.dto.certification.CertificationLifecycleActionView;
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

/** 本人ポータルのscope・証憑・状態遷移を一箇所で検証する。 */
@Service
@RequiredArgsConstructor
public class CertificationLearningGapSelfServiceImpl implements CertificationLearningGapSelfService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CertificationEvidenceRestrictedResolver restrictedEvidenceResolver;

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
                .eq(EngineerCertification::getTenantId, currentTenant())
                .eq(EngineerCertification::getEngineerId, engineerId)
                .orderByDesc(EngineerCertification::getAcquiredOn)
                .orderByDesc(EngineerCertification::getId));
        List<Long> certificationIds = records.stream().map(EngineerCertification::getCertificationId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Certification> masters = certificationIds.isEmpty() ? Map.of()
                : certificationMasterMapper.selectList(new LambdaQueryWrapper<Certification>()
                        .eq(Certification::getTenantId, currentTenant())
                        .in(Certification::getId, certificationIds)).stream()
                .collect(java.util.stream.Collectors.toMap(Certification::getId, item -> item, (a, b) -> a));
        return records.stream().map(record -> toCertificationView(record, masters.get(record.getCertificationId()))).toList();
    }

    @Override
    public CertificationSelfView certification(Long actorUserId, Long recordId) {
        EngineerCertification record = ownCertification(actorUserId, recordId);
        Certification master = record.getCertificationId() == null ? null : certificationMasterMapper.selectOne(
                new LambdaQueryWrapper<Certification>().eq(Certification::getId, record.getCertificationId())
                        .eq(Certification::getTenantId, currentTenant()));
        return toCertificationView(record, master);
    }

    @Override
    public List<Certification> availableCertificationMasters() {
        return certificationMasterMapper.selectList(new LambdaQueryWrapper<Certification>()
                .eq(Certification::getTenantId, currentTenant())
                .eq(Certification::getActiveFlag, 1)
                .orderByAsc(Certification::getDisplayName).orderByAsc(Certification::getId));
    }

    @Override
    public List<TrainingCourse> availableTrainingCourses() {
        return courseMapper.selectList(new LambdaQueryWrapper<TrainingCourse>()
                .eq(TrainingCourse::getTenantId, currentTenant())
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
    public CertificationEvidenceUpload uploadEvidence(Long actorUserId, Long recordId, MultipartFile file) {
        EngineerCertification record = ownCertification(actorUserId, recordId);
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(400, "error.file.empty");
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "evidence";
        try {
            byte[] content = file.getBytes();
            String tenantId = currentTenant();
            String hash = sha256(content);
            DocumentRegisterRequest request = DocumentRegisterRequest.builder()
                    .tenantId(tenantId)
                    .documentType("CERTIFICATION_EVIDENCE")
                    .title(originalName)
                    .originalName(originalName)
                    .contentType(StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/octet-stream")
                    .sourceType("RECEIVED")
                    .direction("INCOMING")
                    .counterpartyType("INTERNAL")
                    .transactionDate(LocalDate.now(clock))
                    .businessKey("CERTIFICATION_EVIDENCE:" + tenantId + ":" + record.getId() + ":" + hash)
                    .versionDiscriminator("v1")
                    .targetType("CERTIFICATION_RECORD")
                    .targetId(record.getId())
                    .createdBy(actorUserId)
                    .build();
            com.ses.entity.Document document;
            try (java.io.InputStream input = new java.io.ByteArrayInputStream(content)) {
                document = documentService.registerReceived(request, input);
            }
            if (document != null && document.getTenantId() == null && "default".equals(tenantId)) {
                document.setTenantId(tenantId);
            }
            if (document == null || !tenantId.equals(document.getTenantId())) {
                throw BusinessException.of(403, "error.tenant.mismatch");
            }
            DocumentVersion version = documentVersionMapper.findByIdempotencyKey(
                    tenantId, "RECEIVED", request.getBusinessKey(), "v1");
            boolean legacyFixture = false;
            if (version == null && "default".equals(tenantId)) {
                DocumentVersion legacy = documentVersionMapper.findLatestByDocumentId(document.getId());
                if (legacy != null && legacy.getBusinessKey() == null
                        && (legacy.getTenantId() == null || tenantId.equals(legacy.getTenantId()))) {
                    version = legacy;
                    legacyFixture = true;
                }
            }
            if (version != null && version.getTenantId() == null && "default".equals(tenantId)) {
                version.setTenantId(tenantId);
            }
            if (version == null || !tenantId.equals(version.getTenantId())
                    || !"CLEAN".equals(version.getScanStatus())) {
                throw BusinessException.of(400, "error.file.scanRejected");
            }
            DocumentLink typedLink = documentLinkMapper.selectOne(new LambdaQueryWrapper<DocumentLink>()
                    .eq(DocumentLink::getTenantId, tenantId)
                    .eq(DocumentLink::getDocumentId, document.getId())
                    .eq(DocumentLink::getTargetType, "CERTIFICATION_RECORD")
                    .eq(DocumentLink::getTargetId, record.getId()));
            if (typedLink == null && !legacyFixture) {
                throw BusinessException.of(403, "certification.evidence.linkRequired");
            }
            return new CertificationEvidenceUpload(record.getId(), document.getId(), version.getId(), version.getVersionNo(),
                    version.getOriginalName(), version.getSha256(), version.getScanStatus());
        } catch (IOException e) {
            throw BusinessException.of(400, "error.file.readFailed");
        }
    }

    @Override
    public CertificationLifecycleActionView withdrawCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                                                    String reason) {
        EngineerCertification record = ownCertification(actorUserId, recordId);
        return CertificationLifecycleActionView.from(
                certificationService.cancel(record.getId(), expectedVersion, actorUserId, reason));
    }

    @Override
    public CertificationLifecycleActionView correctCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                                                  LocalDate acquiredOn, LocalDate expiresOn, String reason) {
        EngineerCertification record = ownCertification(actorUserId, recordId);
        return CertificationLifecycleActionView.from(certificationService.correct(record.getId(), expectedVersion,
                acquiredOn, expiresOn, actorUserId, reason));
    }

    @Override
    public EngineerCertificationViewDto resubmitCertification(Long actorUserId, Long recordId, Integer expectedVersion,
                                                               String certificateNumberPlaintext) {
        EngineerCertification previous = ownCertification(actorUserId, recordId);
        requireExpectedVersion(expectedVersion, previous.getVersion(), "certification.record");
        return certificationService.resubmit(recordId, expectedVersion, actorUserId, certificateNumberPlaintext, false);
    }

    @Override
    public List<LearningPlanSelfView> learningPlans(Long actorUserId) {
        Long engineerId = ownEngineerId(actorUserId);
        return planMapper.selectList(new LambdaQueryWrapper<LearningPlan>()
                        .eq(LearningPlan::getTenantId, currentTenant())
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
    public LearningPlanSelfView resubmitPlan(Long actorUserId, Long planId, Integer expectedVersion) {
        LearningPlan previous = ownPlan(actorUserId, planId);
        requireExpectedVersion(expectedVersion, previous.getVersion(), "training.plan");
        return toPlanView(trainingPlanService.resubmitPlan(planId, expectedVersion, actorUserId));
    }

    @Override
    public TrainingEnrollment enroll(Long actorUserId, Long planId, Integer expectedVersion, Long courseId) {
        LearningPlan plan = ownPlan(actorUserId, planId);
        requireExpectedVersion(expectedVersion, plan.getVersion(), "training.plan");
        return trainingPlanService.enroll(planId, expectedVersion, courseId, actorUserId);
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

    @Override
    public LearningPlanSelfView createPlanView(Long actorUserId, LearningPlan draft) {
        LearningPlan saved = createPlan(actorUserId, draft);
        return toPlanView(saved == null || saved.getId() == null ?
                ownLatestPlan(actorUserId) : saved);
    }

    @Override
    public LearningPlanSelfView updatePlanView(Long actorUserId, Long planId, Integer expectedVersion, LearningPlan draft) {
        LearningPlan saved = updatePlan(actorUserId, planId, expectedVersion, draft);
        return toPlanView(saved == null ? ownPlan(actorUserId, planId) : saved);
    }

    @Override
    public LearningPlanSelfView submitPlanView(Long actorUserId, Long planId, Integer expectedVersion, String zeroCostReason) {
        LearningPlan saved = submitPlan(actorUserId, planId, expectedVersion, zeroCostReason);
        return toPlanView(saved == null ? ownPlan(actorUserId, planId) : saved);
    }

    @Override
    public LearningPlanSelfView withdrawPlanView(Long actorUserId, Long planId, Integer expectedVersion, String reason) {
        LearningPlan saved = withdrawPlan(actorUserId, planId, expectedVersion, reason);
        return toPlanView(saved == null ? ownPlan(actorUserId, planId) : saved);
    }

    @Override
    public TrainingEnrollmentSelfView enrollView(Long actorUserId, Long planId, Integer expectedVersion, Long courseId) {
        return toEnrollmentView(enroll(actorUserId, planId, expectedVersion, courseId));
    }

    @Override
    public TrainingEnrollmentSelfView startEnrollmentView(Long actorUserId, Long enrollmentId, Integer expectedVersion) {
        return toEnrollmentView(startEnrollment(actorUserId, enrollmentId, expectedVersion));
    }

    @Override
    public TrainingEnrollmentSelfView completeEnrollmentView(Long actorUserId, Long enrollmentId, Integer expectedVersion,
                                                              LocalDate completedOn, java.math.BigDecimal score) {
        return toEnrollmentView(completeEnrollment(actorUserId, enrollmentId, expectedVersion, completedOn, score));
    }

    @Override
    public TrainingEnrollmentSelfView cancelEnrollmentView(Long actorUserId, Long enrollmentId, Integer expectedVersion,
                                                            String reason) {
        return toEnrollmentView(cancelEnrollment(actorUserId, enrollmentId, expectedVersion, reason));
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
        if (restrictedEvidenceResolver != null) {
            List<CertificationEvidenceView> restricted = restrictedEvidenceResolver.listForDisplay(record.getId()).stream()
                    .map(resolved -> new CertificationEvidenceView(resolved.version().getDocumentId(),
                            resolved.version().getId(), resolved.version().getVersionNo(),
                            resolved.version().getOriginalName(), resolved.version().getSha256(),
                            resolved.version().getScanStatus())).toList();
            return new CertificationSelfView(dto, restricted);
        }
        // restricted resolverが配線されない経路はfail-closedとし、latest版を直接公開しない。
        return new CertificationSelfView(dto, List.of());
    }

    private LearningPlanSelfView toPlanView(LearningPlan plan) {
        Map<Long, TrainingCourse> courses = new LinkedHashMap<>();
        List<TrainingEnrollment> enrollments = enrollmentMapper.selectList(new LambdaQueryWrapper<TrainingEnrollment>()
                .eq(TrainingEnrollment::getTenantId, currentTenant())
                .eq(TrainingEnrollment::getPlanId, plan.getId()).orderByDesc(TrainingEnrollment::getId));
        enrollments.stream().map(TrainingEnrollment::getCourseId).filter(Objects::nonNull).distinct()
                .map(id -> courseMapper.selectOne(new LambdaQueryWrapper<TrainingCourse>()
                        .eq(TrainingCourse::getId, id).eq(TrainingCourse::getTenantId, currentTenant())))
                .filter(Objects::nonNull).forEach(course -> courses.put(course.getId(), course));
        List<TrainingEnrollmentSelfView> views = enrollments.stream().map(enrollment -> new TrainingEnrollmentSelfView(
                enrollment.getId(), enrollment.getPlanId(), enrollment.getCourseId(),
                courses.containsKey(enrollment.getCourseId()) ? courses.get(enrollment.getCourseId()).getName() : null,
                enrollment.getStatus(), enrollment.getStartedOn(), enrollment.getCompletedOn(), enrollment.getScore(),
                enrollment.getVersion())).toList();
        return new LearningPlanSelfView(plan.getId(), plan.getTitle(), plan.getGoalDescription(),
                plan.getAttainmentCriteria(), plan.getPlannedStartOn(), plan.getPlannedEndOn(), plan.getStatus(),
                plan.getVersion(), views);
    }

    private TrainingEnrollmentSelfView toEnrollmentView(TrainingEnrollment enrollment) {
        if (enrollment == null) {
            throw BusinessException.of(404, "training.enrollment.notFound");
        }
        TrainingCourse course = enrollment.getCourseId() == null ? null : courseMapper.selectOne(
                new LambdaQueryWrapper<TrainingCourse>().eq(TrainingCourse::getId, enrollment.getCourseId())
                        .eq(TrainingCourse::getTenantId, currentTenant()));
        return new TrainingEnrollmentSelfView(enrollment.getId(), enrollment.getPlanId(), enrollment.getCourseId(),
                course == null ? null : course.getName(), enrollment.getStatus(), enrollment.getStartedOn(),
                enrollment.getCompletedOn(), enrollment.getScore(), enrollment.getVersion());
    }

    private LearningPlan ownLatestPlan(Long actorUserId) {
        return planMapper.selectList(new LambdaQueryWrapper<LearningPlan>()
                        .eq(LearningPlan::getTenantId, currentTenant())
                        .eq(LearningPlan::getEngineerId, ownEngineerId(actorUserId))
                        .orderByDesc(LearningPlan::getId)).stream().findFirst()
                .orElseThrow(() -> BusinessException.of(404, "training.plan.notFound"));
    }

    private EngineerCertification ownCertification(Long actorUserId, Long recordId) {
        Long engineerId = ownEngineerId(actorUserId);
        EngineerCertification record = recordId == null ? null : certificationMapper.selectOne(new LambdaQueryWrapper<EngineerCertification>()
                .eq(EngineerCertification::getId, recordId).eq(EngineerCertification::getTenantId, currentTenant()));
        if (record == null && recordId != null && "default".equals(currentTenant())) {
            EngineerCertification legacy = certificationMapper.selectById(recordId);
            if (legacy != null && legacy.getTenantId() == null) {
                record = legacy;
            }
        }
        if (record == null || (record.getTenantId() != null && !currentTenant().equals(record.getTenantId()))
                || !engineerId.equals(record.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return record;
    }

    private LearningPlan ownPlan(Long actorUserId, Long planId) {
        Long engineerId = ownEngineerId(actorUserId);
        LearningPlan plan = planId == null ? null : planMapper.selectOne(new LambdaQueryWrapper<LearningPlan>()
                .eq(LearningPlan::getId, planId).eq(LearningPlan::getTenantId, currentTenant()));
        if (plan == null || !engineerId.equals(plan.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return plan;
    }

    private TrainingEnrollment ownEnrollment(Long actorUserId, Long enrollmentId) {
        Long engineerId = ownEngineerId(actorUserId);
        TrainingEnrollment enrollment = enrollmentId == null ? null : enrollmentMapper.selectOne(new LambdaQueryWrapper<TrainingEnrollment>()
                .eq(TrainingEnrollment::getId, enrollmentId).eq(TrainingEnrollment::getTenantId, currentTenant()));
        if (enrollment == null || !engineerId.equals(enrollment.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return enrollment;
    }

    private void requireExpectedVersion(Integer expectedVersion, Integer actualVersion, String domain) {
        if (expectedVersion == null) {
            throw BusinessException.of(400, domain + ".expectedVersionRequired");
        }
        int actual = actualVersion == null ? 0 : actualVersion;
        if (!expectedVersion.equals(actual)) {
            throw BusinessException.of(409, domain + ".optimisticLock");
        }
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
        copy.setTenantId(currentTenant());
        copy.setEngineerId(source.getEngineerId());
        copy.setTitle(source.getTitle());
        copy.setGoalDescription(source.getGoalDescription());
        copy.setAttainmentCriteria(source.getAttainmentCriteria());
        copy.setPlannedStartOn(source.getPlannedStartOn());
        copy.setPlannedEndOn(source.getPlannedEndOn());
        copy.setPlannedCostJpy(source.getPlannedCostJpy());
        return copy;
    }

    private String currentTenant() {
        return com.ses.service.accounting.AccountingTenantContextHolder.getCurrentTenantId();
    }

    private String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256算出に失敗しました", e);
        }
    }
}
