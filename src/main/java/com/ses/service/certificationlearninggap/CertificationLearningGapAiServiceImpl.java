package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.certificationlearninggap.CertificationLearningGapAiView;
import com.ses.dto.skillgap.SkillGapItem;
import com.ses.dto.skillgap.SkillGapRequest;
import com.ses.dto.skillgap.SkillGapResult;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingCourseSkill;
import com.ses.entity.Project;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingCourseSkillMapper;
import com.ses.service.SkillGapService;
import com.ses.service.skillgap.AiLearningCandidateService;
import com.ses.service.security.DataScopeService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** rule gapのsnapshot/as-ofを正本にし、AIにはallowlistだけを渡す。 */
@Service
@RequiredArgsConstructor
public class CertificationLearningGapAiServiceImpl implements CertificationLearningGapAiService {

    private final CertificationLearningGapQueryService queryService;
    private final SkillGapService skillGapService;
    private final AiLearningCandidateService aiLearningCandidateService;
    private final TrainingCourseSkillMapper courseSkillMapper;
    private final TrainingCourseMapper courseMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProjectMapper projectMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeService dataScopeService;

    @Override
    public CertificationLearningGapAiView suggest(Long engineerId, Long projectId, LocalDate asOf,
                                                   LocalDate periodFrom, LocalDate periodTo,
                                                   SkillGapService.DemandSource demandSource,
                                                   Long actorUserId, Authentication authentication) {
        if (engineerId == null || projectId == null) {
            throw BusinessException.of(400, "skill.gap.requestRequired");
        }
        String tenantId = currentTenant();
        if (projectMapper == null || tenantId == null || tenantId.isBlank()) {
            throw BusinessException.of(403, "error.tenant.contextRequired");
        }
        Project project = projectMapper.selectByIdAndTenant(projectId, tenantId);
        if (project == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (dataScopeService != null) {
            dataScopeService.assertAllowedEngineer(engineerId);
            dataScopeService.assertAllowedProject(projectId);
            if (project != null && project.getCustomerId() != null) {
                dataScopeService.assertAllowedCustomer(project.getCustomerId());
            }
        }
        LocalDate effectiveAsOf = asOf == null ? LocalDate.now(clock) : asOf;
        SkillGapService.DemandSource source = demandSource == null
                ? SkillGapService.DemandSource.COMBINED : demandSource;
        // manager/HR/adminの可視母集団を先に通し、AI endpointだけでscopeを拡張しない。
        queryService.detail(engineerId, new com.ses.dto.certificationlearninggap.CertificationLearningGapFilter(
                engineerId, null, null, null, null, effectiveAsOf, projectId, source), authentication);
        LocalDate from = periodFrom == null ? effectiveAsOf : periodFrom;
        LocalDate to = periodTo == null ? effectiveAsOf : periodTo;
        SkillGapResult ruleGap = skillGapService.calculate(new SkillGapRequest(engineerId, projectId,
                effectiveAsOf, from, to, source, null));
        if (!SkillGapService.STATUS_OK.equals(ruleGap.status())) {
            return new CertificationLearningGapAiView(ruleGap, null);
        }
        List<Long> allowlistedCourseIds = allowlistedCourseIds(ruleGap);
        return new CertificationLearningGapAiView(ruleGap,
                aiLearningCandidateService.suggest(ruleGap, allowlistedCourseIds, effectiveAsOf, actorUserId));
    }

    private List<Long> allowlistedCourseIds(SkillGapResult ruleGap) {
        List<Long> skillIds = ruleGap.items().stream().filter(SkillGapItem::gap)
                .map(SkillGapItem::canonicalSkillId).filter(Objects::nonNull).distinct().toList();
        if (skillIds.isEmpty()) {
            return List.of();
        }
        List<Long> courseIds = courseSkillMapper.selectList(new LambdaQueryWrapper<TrainingCourseSkill>()
                        .eq(TrainingCourseSkill::getTenantId, currentTenant())
                        .in(TrainingCourseSkill::getSkillId, skillIds)
                        .eq(TrainingCourseSkill::getRequiredFlag, 1))
                .stream().map(TrainingCourseSkill::getCourseId).filter(Objects::nonNull).distinct().toList();
        if (courseIds.isEmpty()) {
            return List.of();
        }
        List<TrainingCourse> tenantCourses = courseMapper.selectList(new LambdaQueryWrapper<TrainingCourse>()
                .eq(TrainingCourse::getTenantId, currentTenant())
                .in(TrainingCourse::getId, courseIds));
        return tenantCourses.stream()
                .filter(course -> Integer.valueOf(1).equals(course.getActiveFlag()))
                .map(TrainingCourse::getId).filter(Objects::nonNull).toList();
    }

    private String currentTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }
}
