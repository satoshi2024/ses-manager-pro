package com.ses.service.certificationlearninggap;

import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import com.ses.dto.skillgap.AiCourseCandidateResult;
import com.ses.dto.skillgap.SkillGapItem;
import com.ses.dto.skillgap.SkillGapResult;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingCourseSkill;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingCourseSkillMapper;
import com.ses.service.SkillGapService;
import com.ses.service.skillgap.AiLearningCandidateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationLearningGapAiServiceImplTest {

    @Mock private CertificationLearningGapQueryService queryService;
    @Mock private SkillGapService skillGapService;
    @Mock private AiLearningCandidateService aiLearningCandidateService;
    @Mock private TrainingCourseSkillMapper courseSkillMapper;
    @Mock private TrainingCourseMapper courseMapper;

    @Test
    void asOf期間のruleGapを先に計算しactiveCourseだけをAIallowlistへ渡す() {
        SkillGapItem item = new SkillGapItem("SKILL:1", 1L, "Java", "Java", "CANONICAL", "中級", "初級",
                1, 1, true, true, false, "PROJECT", "PROJECT", List.of(10L), List.of());
        SkillGapResult gap = new SkillGapResult(SkillGapService.STATUS_OK, null, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 42L, 9L,
                SkillGapService.DemandSource.PROJECT, List.of(item), List.of(), 88L);
        TrainingCourseSkill relation = new TrainingCourseSkill();
        relation.setCourseId(77L);
        relation.setSkillId(1L);
        relation.setRequiredFlag(1);
        TrainingCourse active = new TrainingCourse();
        active.setId(77L);
        active.setActiveFlag(1);
        when(queryService.detail(eq(42L), any(), any())).thenReturn(new CertificationLearningGapRow(
                42L, "対象", "稼動中", "ACTIVE", List.of(), List.of(), null, null, 88L, List.of(item)));
        when(skillGapService.calculate(any())).thenReturn(gap);
        when(courseSkillMapper.selectList(any())).thenReturn(List.of(relation));
        when(courseMapper.selectBatchIds(any())).thenReturn(List.of(active));
        AiCourseCandidateResult candidate = new AiCourseCandidateResult("AI_CANDIDATE", gap.asOf(),
                List.of(77L), List.of(77L), "trace", 99L, null, true, true, null, 88L);
        when(aiLearningCandidateService.suggest(eq(gap), eq(List.of(77L)), eq(LocalDate.of(2026, 8, 1)), eq(100L)))
                .thenReturn(candidate);

        var result = new CertificationLearningGapAiServiceImpl(queryService, skillGapService,
                aiLearningCandidateService, courseSkillMapper, courseMapper,
                Clock.fixed(Instant.parse("2026-08-01T03:00:00Z"), ZoneId.of("Asia/Tokyo")))
                .suggest(42L, 9L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31), SkillGapService.DemandSource.PROJECT, 100L,
                        new TestingAuthenticationToken("100", "n", "ROLE_HR"));

        assertEquals(88L, result.ruleGap().snapshotId());
        assertEquals("AI_CANDIDATE", result.aiCandidate().status());
        verify(aiLearningCandidateService).suggest(eq(gap), eq(List.of(77L)), eq(LocalDate.of(2026, 8, 1)), eq(100L));
    }

    @Test
    void historicalDataUnavailableならAIを呼ばずruleGapを維持する() {
        SkillGapResult unavailable = SkillGapResult.unavailable(
                new com.ses.dto.skillgap.SkillGapRequest(42L, 9L, LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31), SkillGapService.DemandSource.COMBINED, null),
                SkillGapService.STATUS_HISTORICAL_DATA_UNAVAILABLE);
        when(queryService.detail(eq(42L), any(), any())).thenReturn(new CertificationLearningGapRow(
                42L, "対象", "稼動中", "ACTIVE", List.of(), List.of(), null, "historical_data_unavailable", null, List.of()));
        when(skillGapService.calculate(any())).thenReturn(unavailable);

        var result = new CertificationLearningGapAiServiceImpl(queryService, skillGapService,
                aiLearningCandidateService, courseSkillMapper, courseMapper, Clock.systemUTC())
                .suggest(42L, 9L, LocalDate.of(2025, 1, 1), null, null,
                        SkillGapService.DemandSource.COMBINED, 100L, null);

        assertEquals(SkillGapService.STATUS_HISTORICAL_DATA_UNAVAILABLE, result.ruleGap().status());
        assertNull(result.aiCandidate());
        verify(aiLearningCandidateService, never()).suggest(any(), any(), any(), any());
    }
}
