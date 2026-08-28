package com.ses.service.certificationlearninggap;

import com.ses.common.exception.BusinessException;
import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.entity.LearningPlan;
import com.ses.mapper.LearningPlanMapper;
import com.ses.service.SkillGapService;
import com.ses.service.training.TrainingPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/** HR/managerの研修費用承認入口。可視母集団を確認してから既存Approval/Expense正本へ委譲する。 */
@Service
@RequiredArgsConstructor
public class CertificationLearningGapTrainingApprovalService {

    private final LearningPlanMapper planMapper;
    private final CertificationLearningGapQueryService queryService;
    private final TrainingPlanService trainingPlanService;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public LearningPlan approve(Long planId, Integer expectedVersion, Long actorUserId, String comment,
                                Authentication authentication) {
        LearningPlan plan = visiblePlan(planId, authentication);
        return trainingPlanService.approve(plan.getId(), expectedVersion, actorUserId, comment);
    }

    @Transactional(rollbackFor = Exception.class)
    public LearningPlan reject(Long planId, Integer expectedVersion, Long actorUserId, String reason,
                               Authentication authentication) {
        LearningPlan plan = visiblePlan(planId, authentication);
        return trainingPlanService.reject(plan.getId(), expectedVersion, actorUserId, reason);
    }

    private LearningPlan visiblePlan(Long planId, Authentication authentication) {
        LearningPlan plan = planId == null ? null : planMapper.selectById(planId);
        if (plan == null) {
            throw BusinessException.of(404, "training.plan.notFound");
        }
        queryService.detail(plan.getEngineerId(), new CertificationLearningGapFilter(plan.getEngineerId(), null,
                null, null, null, LocalDate.now(clock), null, SkillGapService.DemandSource.COMBINED), authentication);
        return plan;
    }
}
