package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiFeedback;
import com.ses.entity.AiOutcome;
import com.ses.entity.AiRecommendationItem;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiFeedbackMapper;
import com.ses.mapper.AiOutcomeMapper;
import com.ses.mapper.AiRecommendationItemMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class AiFeedbackLearningSchemaTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private AiArtifactVersionMapper versionMapper;
    @Autowired
    private AiArtifactVersionService versionService;
    @Autowired
    private AiRecommendationRunMapper runMapper;
    @Autowired
    private AiRecommendationItemMapper itemMapper;
    @Autowired
    private AiFeedbackMapper feedbackMapper;
    @Autowired
    private AiOutcomeMapper outcomeMapper;
    @Autowired
    private AiRecommendationRetentionService retentionService;

    @Test
    void tenant列とrawPrompt列が無い() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            for (String table : List.of(
                    "M_AI_ARTIFACT_VERSION", "T_AI_RECOMMENDATION_RUN",
                    "T_AI_RECOMMENDATION_ITEM", "T_AI_FEEDBACK",
                    "T_AI_OUTCOME", "T_AI_EVALUATION")) {
                List<String> columns = columnNames(meta, table);
                assertTrue(columns.stream().noneMatch(c -> "TENANT_ID".equalsIgnoreCase(c)),
                        table + " に tenant_id がある");
                assertTrue(columns.stream().noneMatch(c ->
                                c.equalsIgnoreCase("RAW_PROMPT")
                                        || c.equalsIgnoreCase("REQUEST_PARAMS")
                                        || c.equalsIgnoreCase("PROMPT")),
                        table + " に raw prompt 列がある: " + columns);
            }
        }
    }

    @Test
    void 同一useCaseのACTIVEは2件作れない() {
        String useCase = uniqueUseCase();
        versionMapper.insert(shadow(useCase, "ACTIVE"));
        AiArtifactVersion second = shadow(useCase, "ACTIVE");
        assertThrows(DataIntegrityViolationException.class, () -> versionMapper.insert(second));
    }

    @Test
    void 同時昇格は片方だけ成功しACTIVEは1つ() throws Exception {
        String useCase = uniqueUseCase();
        AiArtifactVersion baseline = shadow(useCase, "ACTIVE");
        versionMapper.insert(baseline);
        AiArtifactVersion first = shadow(useCase, "SHADOW");
        AiArtifactVersion second = shadow(useCase, "SHADOW");
        versionMapper.insert(first);
        versionMapper.insert(second);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Long id : List.of(first.getId(), second.getId())) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        versionService.promoteToActive(id);
                        success.incrementAndGet();
                    } catch (BusinessException ex) {
                        assertEquals(409, ex.getCode());
                        conflict.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get(), "成功は1件");
        assertEquals(1, conflict.get(), "競合失敗は1件");
        Long activeCount = versionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiArtifactVersion>()
                        .eq(AiArtifactVersion::getUseCase, useCase)
                        .eq(AiArtifactVersion::getStatus, "ACTIVE"));
        assertEquals(1L, activeCount);
    }

    @Test
    void traceからitem_feedback_outcomeへ貫通する() {
        AiArtifactVersion version = versionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiArtifactVersion>()
                        .eq(AiArtifactVersion::getUseCase, "MATCHING")
                        .eq(AiArtifactVersion::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        assertNotNull(version);

        String traceId = UUID.randomUUID().toString();
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTraceId(traceId);
        run.setUseCase("MATCHING");
        run.setArtifactVersionId(version.getId());
        run.setInputHash(HASH);
        run.setRedactedSummaryJson("{\"skills\":[\"Java\"]}");
        run.setCostJpy(0);
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        runMapper.insert(run);

        AiRecommendationItem item = new AiRecommendationItem();
        item.setRunId(run.getId());
        item.setRankNo(1);
        item.setTargetType("ENGINEER");
        item.setTargetId(1L);
        item.setSelectedFlag(1);
        itemMapper.insert(item);

        AiFeedback feedback = new AiFeedback();
        feedback.setItemId(item.getId());
        feedback.setDecision("ACCEPT");
        feedback.setReasonCode("SKILL_MISMATCH");
        feedbackMapper.insert(feedback);

        AiOutcome outcome = new AiOutcome();
        outcome.setItemId(item.getId());
        outcome.setOutcomeType("WIN");
        outcome.setSourceType("PROPOSAL");
        outcome.setSourceId(99L);
        outcome.setOccurredAt(LocalDateTime.now());
        outcomeMapper.insert(outcome);

        AiRecommendationRun loaded = runMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiRecommendationRun>()
                        .eq(AiRecommendationRun::getTraceId, traceId));
        assertEquals(version.getId(), loaded.getArtifactVersionId());
        assertEquals(item.getId(), itemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiRecommendationItem>()
                        .eq(AiRecommendationItem::getRunId, loaded.getId())).get(0).getId());
        assertEquals("ACCEPT", feedbackMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiFeedback>()
                        .eq(AiFeedback::getItemId, item.getId())).get(0).getDecision());
        assertEquals("WIN", outcomeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiOutcome>()
                        .eq(AiOutcome::getItemId, item.getId())).get(0).getOutcomeType());
    }

    @Test
    void outcomeの同一冪等キーは拒否する() {
        AiArtifactVersion version = versionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiArtifactVersion>()
                        .eq(AiArtifactVersion::getUseCase, "MATCHING")
                        .eq(AiArtifactVersion::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTraceId(UUID.randomUUID().toString());
        run.setUseCase("MATCHING");
        run.setArtifactVersionId(version.getId());
        run.setInputHash(HASH);
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        runMapper.insert(run);
        AiRecommendationItem item = new AiRecommendationItem();
        item.setRunId(run.getId());
        item.setRankNo(1);
        item.setTargetType("ENGINEER");
        item.setTargetId(2L);
        itemMapper.insert(item);

        AiOutcome first = new AiOutcome();
        first.setItemId(item.getId());
        first.setOutcomeType("WIN");
        first.setSourceType("PROPOSAL");
        first.setSourceId(7L);
        first.setOccurredAt(LocalDateTime.now());
        outcomeMapper.insert(first);

        AiOutcome duplicate = new AiOutcome();
        duplicate.setItemId(item.getId());
        duplicate.setOutcomeType("WIN");
        duplicate.setSourceType("PROPOSAL");
        duplicate.setSourceId(7L);
        duplicate.setOccurredAt(LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class, () -> outcomeMapper.insert(duplicate));
    }

    @Test
    void 保存期限超過のredactedSummaryはpurgeされrunは残る() {
        AiArtifactVersion version = versionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiArtifactVersion>()
                        .eq(AiArtifactVersion::getUseCase, "CHAT")
                        .eq(AiArtifactVersion::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTraceId(UUID.randomUUID().toString());
        run.setUseCase("CHAT");
        run.setArtifactVersionId(version.getId());
        run.setInputHash(HASH);
        run.setRedactedSummaryJson("{\"ok\":true}");
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        runMapper.insert(run);
        LocalDateTime ancient = LocalDateTime.now().minusDays(800);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE t_ai_recommendation_run SET created_at = ? WHERE id = ?")) {
            ps.setObject(1, ancient);
            ps.setLong(2, run.getId());
            assertEquals(1, ps.executeUpdate());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        int purged = retentionService.purgeExpiredRedactedSummaries(LocalDateTime.now());
        assertTrue(purged >= 1);
        AiRecommendationRun loaded = runMapper.selectById(run.getId());
        assertNotNull(loaded);
        assertNull(loaded.getRedactedSummaryJson());
    }

    private static String uniqueUseCase() {
        return "T110" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static AiArtifactVersion shadow(String useCase, String status) {
        AiArtifactVersion version = new AiArtifactVersion();
        version.setUseCase(useCase);
        version.setProvider("mock");
        version.setModelName("fixture");
        version.setPromptVersion("t110");
        version.setRuleVersion("mock");
        version.setConfigHash(HASH);
        version.setStatus(status);
        version.setStatusVersion(0);
        return version;
    }

    private static List<String> columnNames(DatabaseMetaData meta, String table) throws Exception {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(null, null, table, null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        if (columns.isEmpty()) {
            try (ResultSet rs = meta.getColumns(null, null, table.toLowerCase(), null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        assertTrue(!columns.isEmpty(), table + " の列が取れない");
        return columns;
    }
}
