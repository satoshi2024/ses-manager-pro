package com.ses.service.skillgap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ses.config.AiConfig;
import com.ses.dto.skillgap.AiCourseCandidateResult;
import com.ses.dto.skillgap.SkillGapItem;
import com.ses.dto.skillgap.SkillGapResult;
import com.ses.mapper.LearningDecisionEventMapper;
import com.ses.service.SkillGapService;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiLearningCandidateServiceImplTest {

    @Mock private AiExecutionGateway gateway;
    @Mock private LearningDecisionEventMapper decisionEventMapper;

    @Test
    void AI停止時はruleCourseとgapの正本を維持しgatewayを呼ばない() {
        AiConfig config = new AiConfig();
        SkillGapResult gap = gap();
        AiLearningCandidateService service = service(config);

        AiCourseCandidateResult result = service.suggest(gap, List.of(1L, 2L), date(), 700L);

        assertEquals("RULE_ONLY", result.status());
        assertEquals(List.of(1L, 2L), result.courseIds());
        assertTrue(result.ruleGapPreserved());
        verify(gateway, never()).execute(any());
    }

    @Test
    void AI成功時もallowlist内のcourse候補だけを返し公式判断をしない() {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        when(gateway.execute(any())).thenReturn(new AiGatewayResult("{\"courseIds\":[2,99,1,2]}",
                "trace-1", 55L, "prompt"));
        AiLearningCandidateService service = service(config);

        AiCourseCandidateResult result = service.suggest(gap(), List.of(1L, 2L), date(), 700L);

        assertEquals("AI_CANDIDATE", result.status());
        assertEquals(List.of(2L, 1L), result.aiSuggestedCourseIds());
        assertEquals(55L, result.aiRunId());
        assertEquals(List.of(1L, 2L), result.courseIds());
        ArgumentCaptor<com.ses.service.ai.AiGatewayRequest> request =
                ArgumentCaptor.forClass(com.ses.service.ai.AiGatewayRequest.class);
        verify(gateway).execute(request.capture());
        assertTrue(request.getValue().getAllowlistedFields().containsKey("gapSkillIds"));
        assertTrue(!request.getValue().getAllowlistedFields().containsKey("engineerId"));
    }

    @Test
    void providerErrorとtimeoutはdegradedでもruleGapを欠落させない() {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setLearningCandidateTimeoutMs(20);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            Thread.sleep(200);
            return new AiGatewayResult("{}", "trace-timeout", null, "prompt");
        });
        AiLearningCandidateService service = service(config);

        AiCourseCandidateResult result = service.suggest(gap(), List.of(1L, 2L), date(), 700L);

        assertEquals("DEGRADED", result.status());
        assertEquals("TIMEOUT", result.errorCode());
        assertEquals(List.of(1L, 2L), result.courseIds());
        assertTrue(result.ruleGapPreserved());
    }

    @Test
    void providerErrorもdegradedでruleGapを維持する() {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        when(gateway.execute(any())).thenThrow(new IllegalStateException("provider down"));
        AiLearningCandidateService service = service(config);

        AiCourseCandidateResult result = service.suggest(gap(), List.of(1L, 2L), date(), 700L);

        assertEquals("DEGRADED", result.status());
        assertEquals("PROVIDER_ERROR", result.errorCode());
        assertEquals(List.of(1L, 2L), result.courseIds());
        assertTrue(result.ruleGapPreserved());
    }

    @Test
    void AI候補acceptは人のdecision監査だけで公式projectionへ触れない() {
        AiConfig config = new AiConfig();
        AiLearningCandidateService service = service(config);
        AiCourseCandidateResult candidate = new AiCourseCandidateResult("AI_CANDIDATE", date(),
                List.of(1L), List.of(1L), "trace-1", 55L, null, true, true,
                java.time.LocalDateTime.of(2026, 8, 28, 10, 0), 20L);

        service.accept(candidate, 900L, "人がcourse候補を確認");

        ArgumentCaptor<com.ses.entity.LearningDecisionEvent> event =
                ArgumentCaptor.forClass(com.ses.entity.LearningDecisionEvent.class);
        verify(decisionEventMapper).insertEvent(event.capture());
        assertEquals("LEARNING_SUGGESTION_ACCEPT", event.getValue().getDecisionDomain());
        assertEquals("AI_COURSE_CANDIDATE", event.getValue().getSourceType());
        assertEquals(55L, event.getValue().getSourceId());
        assertEquals(900L, event.getValue().getHumanActorUserId());
    }

    @Test
    void AI候補以外は人のaccept対象にできない() {
        AiLearningCandidateService service = service(new AiConfig());
        AiCourseCandidateResult disabled = new AiCourseCandidateResult("RULE_ONLY", date(),
                List.of(1L), List.of(), null, null, "AI_DISABLED", true, false, null, null);

        org.junit.jupiter.api.Assertions.assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.accept(disabled, 900L, "人が確認"));
    }

    @Test
    void AI候補のrejectもhumanDecisionとして監査する() {
        AiLearningCandidateService service = service(new AiConfig());
        AiCourseCandidateResult candidate = new AiCourseCandidateResult("AI_CANDIDATE", date(),
                List.of(1L), List.of(1L), "trace-1", 55L, null, true, true,
                java.time.LocalDateTime.of(2026, 8, 28, 10, 0), 20L);

        service.reject(candidate, 900L, "人が不採用");

        ArgumentCaptor<com.ses.entity.LearningDecisionEvent> event =
                ArgumentCaptor.forClass(com.ses.entity.LearningDecisionEvent.class);
        verify(decisionEventMapper).insertEvent(event.capture());
        assertEquals("LEARNING_SUGGESTION_REJECT", event.getValue().getDecisionDomain());
    }

    @Test
    void 期限切れcandidateはhumanDecisionを記録しない() {
        AiLearningCandidateService service = service(new AiConfig());
        AiCourseCandidateResult expired = new AiCourseCandidateResult("AI_CANDIDATE", date(),
                List.of(1L), List.of(1L), "trace-1", 55L, null, true, true,
                java.time.LocalDateTime.of(2026, 8, 28, 8, 59), 20L);

        org.junit.jupiter.api.Assertions.assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.reject(expired, 900L, "期限後判断"));
        verify(decisionEventMapper, never()).insertEvent(any());
    }

    private AiLearningCandidateService service(AiConfig config) {
        return new AiLearningCandidateServiceImpl(gateway, decisionEventMapper, config,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("Asia/Tokyo")));
    }

    private SkillGapResult gap() {
        SkillGapItem item = new SkillGapItem("SKILL:1", 1L, "Java", "Java", "CANONICAL",
                "中級", "初級", 1, 1, true, true, false, "PROJECT", "PROJECT", List.of(10L), List.of(11L));
        return new SkillGapResult(SkillGapService.STATUS_OK, null, date(), date(), date(),
                10L, 20L, SkillGapService.DemandSource.PROJECT, List.of(item), List.of(), null);
    }

    private LocalDate date() {
        return LocalDate.of(2026, 8, 28);
    }
}
