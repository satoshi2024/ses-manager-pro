package com.ses.service.certificationlearninggap;

import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import com.ses.entity.LearningPlan;
import com.ses.mapper.LearningPlanMapper;
import com.ses.service.training.TrainingPlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationLearningGapTrainingApprovalServiceTest {

    @Mock private LearningPlanMapper planMapper;
    @Mock private CertificationLearningGapQueryService queryService;
    @Mock private TrainingPlanService trainingPlanService;

    @Test
    void approvalは同じvisiblePopulation確認後に既存TrainingPlanServiceへ委譲する() {
        LearningPlan plan = new LearningPlan();
        plan.setId(55L);
        plan.setEngineerId(42L);
        LearningPlan approved = new LearningPlan();
        approved.setId(55L);
        approved.setStatus(TrainingPlanService.PLAN_APPROVED);
        when(planMapper.selectById(55L)).thenReturn(plan);
        when(queryService.detail(eq(42L), any(), any())).thenReturn(new CertificationLearningGapRow(
                42L, "対象", "稼動中", "ACTIVE", List.of(), List.of(), null, null, null, List.of()));
        when(trainingPlanService.approve(55L, 3, 8L, "確認済み")).thenReturn(approved);

        var result = new CertificationLearningGapTrainingApprovalService(planMapper, queryService,
                trainingPlanService, Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneId.of("Asia/Tokyo")))
                .approve(55L, 3, 8L, "確認済み", new TestingAuthenticationToken("8", "n", "ROLE_HR"));

        assertEquals(TrainingPlanService.PLAN_APPROVED, result.getStatus());
        verify(queryService).detail(eq(42L), any(), any());
        verify(trainingPlanService).approve(55L, 3, 8L, "確認済み");
    }
}
