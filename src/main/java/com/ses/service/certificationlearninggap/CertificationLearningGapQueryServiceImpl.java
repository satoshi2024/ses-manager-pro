package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.dto.certificationlearninggap.CertificationLearningGapCertificationDto;
import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import com.ses.dto.certificationlearninggap.CertificationLearningGapTrainingDto;
import com.ses.dto.skillgap.SkillGapRequest;
import com.ses.dto.skillgap.SkillGapResult;
import com.ses.entity.Certification;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCertification;
import com.ses.entity.LearningPlan;
import com.ses.entity.LifecycleCase;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingEnrollment;
import com.ses.mapper.CertificationMapper;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A1の共通query。母集団を先に確定し、そのID集合からだけ資格・plan・gapを投影する。
 * detail/count/exportが別々のscope実装を持たないことが重要な境界である。
 */
@Service
@RequiredArgsConstructor
public class CertificationLearningGapQueryServiceImpl implements CertificationLearningGapQueryService {

    private static final String PII_ACTION = "certification.pii.view";
    private static final String DEFAULT_LIFECYCLE = "ACTIVE";
    private static final String RESIGNED = "RESIGNED";
    private static final String ON_LEAVE = "ON_LEAVE";

    private final EngineerService engineerService;
    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;
    private final AuthorizationService authorizationService;
    private final EngineerCertificationMapper certificationRecordMapper;
    private final CertificationMapper certificationMapper;
    private final LearningPlanMapper learningPlanMapper;
    private final TrainingEnrollmentMapper enrollmentMapper;
    private final TrainingCourseMapper courseMapper;
    private final LifecycleCaseMapper lifecycleCaseMapper;
    private final SkillGapService skillGapService;
    private final CertificationNumberCryptoService numberCryptoService;

    @Override
    public Page<CertificationLearningGapRow> page(CertificationLearningGapFilter filter, long current, long size,
                                                  Authentication authentication) {
        Page<CertificationLearningGapRow> page = PageUtils.safePage(current, size);
        List<CertificationLearningGapRow> all = rows(filter, authentication, true);
        int from = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        int to = (int) Math.min(from + page.getSize(), all.size());
        Page<CertificationLearningGapRow> result = new Page<>(page.getCurrent(), page.getSize(), all.size());
        result.setRecords(all.subList(from, to));
        return result;
    }

    @Override
    public long count(CertificationLearningGapFilter filter, Authentication authentication) {
        return rows(filter, authentication, false).size();
    }

    @Override
    public CertificationLearningGapRow detail(Long engineerId, CertificationLearningGapFilter filter,
                                              Authentication authentication) {
        if (engineerId == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        CertificationLearningGapFilter detailFilter = new CertificationLearningGapFilter(
                engineerId, filter == null ? null : filter.engineerName(),
                filter == null ? null : filter.engineerStatus(),
                filter == null ? null : filter.lifecycleState(),
                filter == null ? null : filter.certificationState(),
                filter == null ? null : filter.asOf(),
                filter == null ? null : filter.projectId(),
                filter == null ? null : filter.demandSource());
        return rows(detailFilter, authentication, true).stream().findFirst()
                .orElseThrow(() -> BusinessException.of(404, "error.scope.notFound"));
    }

    @Override
    public List<CertificationLearningGapRow> export(CertificationLearningGapFilter filter,
                                                    Authentication authentication) {
        // exportは同じ母集団を使うが、raw資格番号を常にomitする。
        return rows(filter, authentication, false);
    }

    @Override
    public Set<Long> visibleEngineerIds(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        Set<Long> dataIds = dataScopeService.isScoped() ? safeSet(dataScopeService.allowedEngineerIds()) : null;
        if (organizationScopeService.hasFullAccess()) {
            return dataIds == null ? null : Set.copyOf(dataIds);
        }
        return organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedEngineerIds(date), dataIds);
    }

    private List<CertificationLearningGapRow> rows(CertificationLearningGapFilter rawFilter,
                                                   Authentication authentication, boolean includeFullNumber) {
        CertificationLearningGapFilter filter = normalize(rawFilter);
        Set<Long> allowedIds = visibleEngineerIds(filter.asOf());
        if (allowedIds != null && allowedIds.isEmpty()) {
            return List.of();
        }
        // DB wrapperだけに依存せず、detailのID直指定も同じpopulationでfail closedにする。
        if (allowedIds != null && filter.engineerId() != null && !allowedIds.contains(filter.engineerId())) {
            return List.of();
        }

        LambdaQueryWrapper<Engineer> engineerQuery = new LambdaQueryWrapper<>();
        if (allowedIds != null) {
            engineerQuery.in(Engineer::getId, allowedIds);
        }
        if (filter.engineerId() != null) {
            engineerQuery.eq(Engineer::getId, filter.engineerId());
        }
        if (StringUtils.hasText(filter.engineerName())) {
            engineerQuery.like(Engineer::getFullName, filter.engineerName());
        }
        if (StringUtils.hasText(filter.engineerStatus())) {
            engineerQuery.eq(Engineer::getStatus, filter.engineerStatus());
        }
        engineerQuery.orderByDesc(Engineer::getId);
        List<Engineer> engineers = engineerService.list(engineerQuery);
        if (engineers.isEmpty()) {
            return List.of();
        }
        Set<Long> engineerIds = engineers.stream().map(Engineer::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> lifecycleStates = lifecycleStates(engineerIds);
        engineers = engineers.stream()
                .filter(e -> matchesLifecycle(lifecycleStates.getOrDefault(e.getId(), DEFAULT_LIFECYCLE), filter.lifecycleState()))
                .toList();
        if (engineers.isEmpty()) {
            return List.of();
        }

        List<Long> ids = engineers.stream().map(Engineer::getId).toList();
        Map<Long, List<EngineerCertification>> certifications = groupCertifications(ids);
        Map<Long, Certification> masters = certificationMasters(certifications.values().stream()
                .flatMap(List::stream).map(EngineerCertification::getCertificationId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, List<LearningPlan>> plans = groupPlans(ids);
        Map<Long, List<TrainingEnrollment>> enrollments = groupEnrollments(ids);
        Set<Long> courseIds = enrollments.values().stream().flatMap(List::stream)
                .map(TrainingEnrollment::getCourseId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, TrainingCourse> courses = courseIds.isEmpty() ? Map.of() : courseMapper.selectBatchIds(courseIds).stream()
                .collect(Collectors.toMap(TrainingCourse::getId, Function.identity(), (a, b) -> a));
        boolean canViewFullNumber = includeFullNumber && authorizationService.isAllowed(authentication, PII_ACTION);

        List<CertificationLearningGapRow> result = new ArrayList<>();
        for (Engineer engineer : engineers) {
            List<CertificationLearningGapCertificationDto> certRows = certifications.getOrDefault(engineer.getId(), List.of())
                    .stream()
                    .map(record -> certificationRow(record, masters.get(record.getCertificationId()), filter.asOf(), canViewFullNumber))
                    .filter(row -> matchesCertification(row.effectiveState(), filter.certificationState()))
                    .toList();
            if (StringUtils.hasText(filter.certificationState()) && certRows.isEmpty()) {
                continue;
            }
            List<CertificationLearningGapTrainingDto> trainingRows = trainingRows(
                    plans.getOrDefault(engineer.getId(), List.of()), enrollments.getOrDefault(engineer.getId(), List.of()), courses);
            SkillGapResult gap = calculateGap(engineer.getId(), filter, authentication);
            result.add(new CertificationLearningGapRow(
                    engineer.getId(), engineer.getFullName(), engineer.getStatus(),
                    lifecycleStates.getOrDefault(engineer.getId(), DEFAULT_LIFECYCLE),
                    certRows, trainingRows,
                    gap == null ? null : gap.status(),
                    gap == null ? null : gap.unavailableReason(),
                    gap == null ? null : gap.snapshotId(),
                    gap == null || gap.items() == null ? List.of() : gap.items()));
        }
        return result;
    }

    private CertificationLearningGapFilter normalize(CertificationLearningGapFilter filter) {
        if (filter == null) {
            return new CertificationLearningGapFilter(null, null, null, null, null, LocalDate.now(), null,
                    SkillGapService.DemandSource.COMBINED);
        }
        LocalDate asOf = filter.asOf() == null ? LocalDate.now() : filter.asOf();
        SkillGapService.DemandSource source = filter.demandSource() == null
                ? SkillGapService.DemandSource.COMBINED : filter.demandSource();
        return new CertificationLearningGapFilter(filter.engineerId(), filter.engineerName(), filter.engineerStatus(),
                filter.lifecycleState(), filter.certificationState(), asOf, filter.projectId(), source);
    }

    private SkillGapResult calculateGap(Long engineerId, CertificationLearningGapFilter filter,
                                        Authentication authentication) {
        if (filter.projectId() == null) {
            return null;
        }
        // SkillGapService自身がeffective eventだけを読み、SELF/MANAGER assessmentを読まない。
        return skillGapService.calculate(new SkillGapRequest(engineerId, filter.projectId(), filter.asOf(),
                filter.asOf(), filter.asOf(), filter.demandSource(), null));
    }

    private Map<Long, List<EngineerCertification>> groupCertifications(List<Long> ids) {
        return certificationRecordMapper.selectList(new LambdaQueryWrapper<EngineerCertification>()
                        .in(EngineerCertification::getEngineerId, ids)
                        .orderByDesc(EngineerCertification::getAcquiredOn)
                        .orderByDesc(EngineerCertification::getId))
                .stream().collect(Collectors.groupingBy(EngineerCertification::getEngineerId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, Certification> certificationMasters(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return certificationMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Certification::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, List<LearningPlan>> groupPlans(List<Long> ids) {
        return learningPlanMapper.selectList(new LambdaQueryWrapper<LearningPlan>()
                        .in(LearningPlan::getEngineerId, ids)
                        .orderByDesc(LearningPlan::getPlannedStartOn)
                        .orderByDesc(LearningPlan::getId))
                .stream().collect(Collectors.groupingBy(LearningPlan::getEngineerId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, List<TrainingEnrollment>> groupEnrollments(List<Long> ids) {
        return enrollmentMapper.selectList(new LambdaQueryWrapper<TrainingEnrollment>()
                        .in(TrainingEnrollment::getEngineerId, ids)
                        .orderByDesc(TrainingEnrollment::getId))
                .stream().collect(Collectors.groupingBy(TrainingEnrollment::getEngineerId, LinkedHashMap::new, Collectors.toList()));
    }

    private List<CertificationLearningGapTrainingDto> trainingRows(List<LearningPlan> plans,
                                                                    List<TrainingEnrollment> enrollments,
                                                                    Map<Long, TrainingCourse> courses) {
        Map<Long, List<TrainingEnrollment>> byPlan = enrollments.stream()
                .filter(e -> e.getPlanId() != null)
                .collect(Collectors.groupingBy(TrainingEnrollment::getPlanId, LinkedHashMap::new, Collectors.toList()));
        List<CertificationLearningGapTrainingDto> result = new ArrayList<>();
        for (LearningPlan plan : plans) {
            List<TrainingEnrollment> planEnrollments = byPlan.getOrDefault(plan.getId(), List.of());
            if (planEnrollments.isEmpty()) {
                result.add(new CertificationLearningGapTrainingDto(plan.getId(), plan.getTitle(), plan.getStatus(),
                        plan.getPlannedStartOn(), plan.getPlannedEndOn(), plan.getPlannedCostJpy(), null, null, null, null));
            } else {
                for (TrainingEnrollment enrollment : planEnrollments) {
                    TrainingCourse course = courses.get(enrollment.getCourseId());
                    result.add(new CertificationLearningGapTrainingDto(plan.getId(), plan.getTitle(), plan.getStatus(),
                            plan.getPlannedStartOn(), plan.getPlannedEndOn(), plan.getPlannedCostJpy(), enrollment.getId(),
                            enrollment.getStatus(), course == null ? null : course.getName(), enrollment.getCompletedOn()));
                }
            }
        }
        return List.copyOf(result);
    }

    private CertificationLearningGapCertificationDto certificationRow(EngineerCertification record,
                                                                       Certification master,
                                                                       LocalDate asOf,
                                                                       boolean canViewFullNumber) {
        String effectiveState = record.getRecordState();
        if ("ACTIVE".equals(record.getRecordState()) && record.getExpiresOn() != null
                && record.getExpiresOn().isBefore(asOf)) {
            effectiveState = "EXPIRED";
        }
        String raw = null;
        if (canViewFullNumber && record.getCertificateNumberEncrypted() != null) {
            raw = numberCryptoService.decrypt(record.getTenantId(), record.getId(), record.getCertificateNumberEncrypted(),
                    record.getCertificateNumberKeyVersion(), record.getCertificateNumberCipherFormat());
        }
        return new CertificationLearningGapCertificationDto(record.getId(), record.getCertificationId(),
                master == null ? null : master.getDisplayName(), record.getAcquiredOn(), record.getExpiresOn(),
                record.getRecordState(), effectiveState, record.getCurrentFlag(), record.getCertificateNumberMasked(), raw,
                canViewFullNumber);
    }

    private Map<Long, String> lifecycleStates(Set<Long> engineerIds) {
        if (engineerIds.isEmpty()) return Map.of();
        List<LifecycleCase> cases = lifecycleCaseMapper.selectList(new LambdaQueryWrapper<LifecycleCase>()
                .in(LifecycleCase::getEngineerId, engineerIds)
                .in(LifecycleCase::getLifecycleType, "LEAVE", "REINSTATEMENT", "RESIGNATION")
                .ne(LifecycleCase::getStatus, "CANCELLED")
                .orderByDesc(LifecycleCase::getAnchorDate)
                .orderByDesc(LifecycleCase::getId));
        Map<Long, String> result = new HashMap<>();
        for (LifecycleCase lifecycleCase : cases) {
            result.putIfAbsent(lifecycleCase.getEngineerId(), lifecycleState(lifecycleCase));
        }
        return result;
    }

    private String lifecycleState(LifecycleCase lifecycleCase) {
        if ("RESIGNATION".equals(lifecycleCase.getLifecycleType()) && "COMPLETED".equals(lifecycleCase.getStatus())) {
            return RESIGNED;
        }
        if ("LEAVE".equals(lifecycleCase.getLifecycleType())
                && ("ACTIVE".equals(lifecycleCase.getStatus()) || "ON_HOLD".equals(lifecycleCase.getStatus())
                || "COMPLETED".equals(lifecycleCase.getStatus()))) {
            return ON_LEAVE;
        }
        if ("REINSTATEMENT".equals(lifecycleCase.getLifecycleType()) && "COMPLETED".equals(lifecycleCase.getStatus())) {
            return DEFAULT_LIFECYCLE;
        }
        return "PENDING";
    }

    private boolean matchesLifecycle(String actual, String requested) {
        return !StringUtils.hasText(requested) || requested.equals(actual);
    }

    private boolean matchesCertification(String actual, String requested) {
        return !StringUtils.hasText(requested) || requested.equals(actual);
    }

    private Set<Long> safeSet(Set<Long> ids) {
        return ids == null ? Set.of() : new HashSet<>(ids);
    }
}
