package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AiFeedback;
import com.ses.entity.AiOutcome;
import com.ses.entity.Proposal;
import com.ses.mapper.AiFeedbackMapper;
import com.ses.mapper.AiOutcomeMapper;
import com.ses.service.ai.impl.AiOutcomeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "1", roles = "管理者")
@Transactional
class AiFeedbackOutcomeTest {

    @Autowired
    private AiFeedbackService feedbackService;
    @Autowired
    private AiOutcomeService outcomeService;
    @Autowired
    private AiOutcomeServiceImpl outcomeServiceImpl;
    @Autowired
    private AiRecommendationRecorder recorder;
    @Autowired
    private AiFeedbackMapper feedbackMapper;
    @Autowired
    private AiOutcomeMapper outcomeMapper;

    @Test
    void 未判断は却下ではなくfeedbackを残せる() {
        Long itemId = newItem();
        AiFeedback none = feedbackService.record(itemId, null, null, null);
        assertEquals(null, none.getDecision());
        long rejects = feedbackMapper.selectList(null).stream()
                .filter(f -> itemId.equals(f.getItemId()) && "REJECT".equals(f.getDecision()))
                .count();
        assertEquals(0, rejects);
    }

    @Test
    void 重複outcomeは1件() {
        Long itemId = newItem();
        Proposal proposal = new Proposal();
        proposal.setId(9001L);
        proposal.setAiItemId(itemId);
        proposal.setStatus("成約");
        outcomeService.onProposalStatusChanged(proposal);
        outcomeService.onProposalStatusChanged(proposal);
        long wins = outcomeMapper.selectList(null).stream()
                .filter(o -> itemId.equals(o.getItemId()) && "WIN".equals(o.getOutcomeType()))
                .count();
        assertEquals(1, wins);
    }

    @Test
    void 当日解約はEARLY_EXITにしない() {
        Long itemId = newItem();
        assertFalse(AiOutcomeServiceImpl.isEarlyExit(
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 20)));
        outcomeServiceImpl.recordEarlyExit(itemId, 8001L,
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 20));
        assertEquals(0, countEarly(itemId));
        outcomeServiceImpl.recordEarlyExit(itemId, 8001L,
                LocalDate.of(2026, 12, 31), LocalDate.of(2026, 8, 20));
        assertEquals(1, countEarly(itemId));
        outcomeServiceImpl.recordEarlyExit(itemId, 8001L,
                LocalDate.of(2026, 12, 31), LocalDate.of(2026, 8, 20));
        assertEquals(1, countEarly(itemId));
    }

    @Test
    void 営業は他人のrunにfeedbackできない() {
        Long itemId = newItem(10L);
        setRole("11", "営業");
        BusinessException denied = assertThrows(BusinessException.class,
                () -> feedbackService.record(itemId, "REJECT", null, null));
        assertEquals(403, denied.getCode());
        setRole("10", "営業");
        AiFeedback own = feedbackService.record(itemId, "HOLD", null, null);
        assertEquals("HOLD", own.getDecision());
    }

    @Test
    void WINはEXISTSで提案源1件() {
        Long itemId = newItem();
        Proposal proposal = new Proposal();
        proposal.setId(9003L);
        proposal.setAiItemId(itemId);
        proposal.setStatus("成約");
        outcomeService.onProposalStatusChanged(proposal);
        List<AiOutcome> wins = outcomeMapper.selectList(null).stream()
                .filter(o -> itemId.equals(o.getItemId()) && "WIN".equals(o.getOutcomeType()))
                .toList();
        assertEquals(1, wins.size());
        assertTrue(wins.stream().anyMatch(o -> "PROPOSAL".equals(o.getSourceType())));
    }

    private long countEarly(Long itemId) {
        return outcomeMapper.selectList(null).stream()
                .filter(o -> itemId.equals(o.getItemId()) && "EARLY_EXIT".equals(o.getOutcomeType()))
                .count();
    }

    private Long newItem() {
        return newItem(1L);
    }

    private Long newItem(Long actorUserId) {
        var dto = new com.ses.dto.ai.MatchResultDto();
        dto.setProjectId(101L);
        dto.setScore(80);
        dto.setReason("ok");
        recorder.recordMatch("MATCHING", actorUserId, List.of(dto));
        return dto.getItemId();
    }

    private static void setRole(String userId, String role) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        userId, "x",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))));
    }
}
