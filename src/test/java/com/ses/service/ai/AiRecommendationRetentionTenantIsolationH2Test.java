package com.ses.service.ai;

import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.impl.AiRecommendationRetentionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF08: AI retention purgeは明示tenantだけを掃除し、他tenantへ波及しない。 */
@SpringBootTest
@ActiveProfiles("test")
class AiRecommendationRetentionTenantIsolationH2Test {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private AiRecommendationRetentionService retentionService;
    @Autowired
    private AiRecommendationRunMapper runMapper;
    @Autowired
    private AiArtifactVersionMapper versionMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Clock clock;

    @BeforeEach
    @AfterEach
    void clearTenantAndFixtures() {
        AccountingTenantContextHolder.clear();
        jdbcTemplate.update("DELETE FROM t_ai_recommendation_run WHERE trace_id LIKE 'ret-iso-%'");
    }

    @Test
    void 双tenant過期データでも現在tenantだけpurgeする() {
        Long versionId = activeVersionId();
        Long runA = insertExpired("tenant-a", versionId);
        Long runB = insertExpired("tenant-b", versionId);

        int purged = AccountingTenantContextHolder.runWithTenant("tenant-a",
                () -> retentionService.purgeExpiredRedactedSummaries(LocalDateTime.now(clock), 100));

        assertTrue(purged >= 1);
        assertNull(runMapper.selectById(runA).getRedactedSummaryJson());
        assertNotNull(runMapper.selectById(runB).getRedactedSummaryJson());
    }

    @Test
    void tenant欠落時は例外を投げどの行も消さない() {
        Long versionId = activeVersionId();
        Long runA = insertExpired("tenant-a", versionId);
        AccountingTenantContextHolder.clear();

        assertThrows(IllegalStateException.class,
                () -> retentionService.purgeExpiredRedactedSummaries(LocalDateTime.now(clock), 100));

        assertNotNull(runMapper.selectById(runA).getRedactedSummaryJson());
    }

    @Test
    void 繰り返しても他tenantは残る() {
        Long versionId = activeVersionId();
        Long runA = insertExpired("tenant-a", versionId);
        Long runB = insertExpired("tenant-b", versionId);

        AccountingTenantContextHolder.runWithTenant("tenant-a",
                () -> retentionService.purgeExpiredRedactedSummaries(LocalDateTime.now(clock), 100));
        AccountingTenantContextHolder.runWithTenant("tenant-a",
                () -> retentionService.purgeExpiredRedactedSummaries(LocalDateTime.now(clock), 100));

        assertNull(runMapper.selectById(runA).getRedactedSummaryJson());
        assertNotNull(runMapper.selectById(runB).getRedactedSummaryJson());
    }

    @Test
    void mapperのnulltenantoverloadは存在しない() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/ses/mapper/AiRecommendationRunMapper.java"));
        assertTrue(source.contains("AND tenant_id = #{tenantId}"));
        assertTrue(!source.contains("IS NULL OR tenant_id"));
        assertTrue(!source.contains("purgeExpiredSummaries(LocalDateTime cutoff"));
    }

    private Long activeVersionId() {
        AiArtifactVersion version = versionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiArtifactVersion>()
                        .eq(AiArtifactVersion::getUseCase, "CHAT")
                        .eq(AiArtifactVersion::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        if (version != null) {
            return version.getId();
        }
        AiArtifactVersion created = new AiArtifactVersion();
        created.setUseCase("CHAT");
        created.setProvider("mock");
        created.setModelName("fixture");
        created.setPromptVersion("ret");
        created.setRuleVersion("mock");
        created.setConfigHash(HASH);
        created.setStatus("ACTIVE");
        created.setStatusVersion(0);
        versionMapper.insert(created);
        return created.getId();
    }

    private Long insertExpired(String tenantId, Long versionId) {
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTenantId(tenantId);
        run.setTraceId(("ret-iso-" + UUID.randomUUID()).substring(0, 36));
        run.setUseCase("CHAT");
        run.setArtifactVersionId(versionId);
        run.setInputHash(HASH);
        run.setRedactedSummaryJson("{\"ok\":true}");
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        runMapper.insert(run);
        LocalDateTime ancient = LocalDateTime.now(clock).minusDays(800);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE t_ai_recommendation_run SET created_at = ? WHERE id = ?")) {
            ps.setObject(1, ancient);
            ps.setLong(2, run.getId());
            assertEquals(1, ps.executeUpdate());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return run.getId();
    }
}
