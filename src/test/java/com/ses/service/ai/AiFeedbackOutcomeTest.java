package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AiFeedback;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationItem;
import com.ses.entity.AiRecommendationRun;
import com.ses.entity.AiOutcome;
import com.ses.entity.Proposal;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiFeedbackMapper;
import com.ses.mapper.AiOutcomeMapper;
import com.ses.mapper.AiRecommendationItemMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.impl.AiOutcomeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void bindTenant() {
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("default");
        jdbcTemplate.update("INSERT INTO m_customer (id, tenant_id, company_name, deleted_flag, version) "
                + "SELECT 1, 'default', 'AI feedback fixture customer', 0, 0 "
                + "WHERE NOT EXISTS (SELECT 1 FROM m_customer WHERE id = 1)");
        jdbcTemplate.update("UPDATE m_customer SET tenant_id = 'default', deleted_flag = 0 WHERE id = 1");
        jdbcTemplate.update("INSERT INTO t_project (id, project_name, customer_id, status, created_at, updated_at, deleted_flag) "
                + "SELECT 101, 'AI feedback fixture', 1, '募集中', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0 "
                + "WHERE NOT EXISTS (SELECT 1 FROM t_project WHERE id = 101)");
        jdbcTemplate.update("UPDATE t_project SET customer_id = 1, deleted_flag = 0 WHERE id = 101");
    }

    @AfterEach
    void clearTenant() {
        com.ses.service.accounting.AccountingTenantContextHolder.clear();
    }

    @Autowired
    private AiFeedbackService feedbackService;
    @Autowired
    private AiOutcomeService outcomeService;
    @Autowired
    private AiOutcomeServiceImpl outcomeServiceImpl;
    @Autowired
    private AiArtifactVersionMapper artifactVersionMapper;
    @Autowired
    private AiRecommendationRunMapper recommendationRunMapper;
    @Autowired
    private AiRecommendationItemMapper recommendationItemMapper;
    @Autowired
    private AiFeedbackMapper feedbackMapper;
    @Autowired
    private AiOutcomeMapper outcomeMapper;

    @Test
    void scopeContract未完成の旧feedbackは保存しない() {
        Long itemId = newItem();
        BusinessException denied = assertThrows(BusinessException.class,
                () -> feedbackService.record(new AiFeedbackService.FeedbackCommand(
                        itemId, 1L, null, null, null), null));
        assertEquals(403, denied.getCode());
        assertEquals(0, feedbackMapper.selectList(null).stream()
                .filter(f -> itemId.equals(f.getItemId())).count());
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
    void contextのない旧feedbackはactorに関係なく拒否する() {
        Long itemId = newItem(10L);
        setRole("11", "営業");
        BusinessException denied = assertThrows(BusinessException.class,
                () -> feedbackService.record(new AiFeedbackService.FeedbackCommand(
                        itemId, 1L, "REJECT", null, null), null));
        assertEquals(403, denied.getCode());
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
        AiArtifactVersion artifact = artifactVersionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiArtifactVersion>()
                        .eq(AiArtifactVersion::getUseCase, "MATCHING")
                        .eq(AiArtifactVersion::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTenantId("default");
        run.setTraceId(UUID.randomUUID().toString());
        run.setUseCase("MATCHING");
        run.setArtifactVersionId(artifact.getId());
        run.setActorUserId(actorUserId);
        run.setInputHash("1".repeat(64));
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        recommendationRunMapper.insert(run);

        AiRecommendationItem item = new AiRecommendationItem();
        item.setTenantId("default");
        item.setRunId(run.getId());
        item.setRankNo(1);
        item.setTargetType("PROJECT");
        item.setTargetId(101L);
        item.setSelectedFlag(0);
        recommendationItemMapper.insert(item);
        return item.getId();
    }

    private static void setRole(String userId, String role) {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                userId, "x",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)));
        auth.setDetails(java.util.Map.of("tenant_id", "default"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
