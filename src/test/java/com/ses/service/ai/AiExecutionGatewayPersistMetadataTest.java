package com.ses.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF08: gateway persistRunはExecutionContextがある場合Recorderと同水準のmetadataを落とす。 */
@SpringBootTest
@ActiveProfiles("test")
class AiExecutionGatewayPersistMetadataTest {

    @Autowired
    private AiExecutionGateway gateway;
    @Autowired
    private AiRecommendationRunMapper runMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void bindTenant() {
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void clear() {
        AccountingTenantContextHolder.clear();
        jdbcTemplate.update("DELETE FROM t_ai_recommendation_run WHERE trace_id LIKE 'gw-meta-%'");
    }

    @Test
    void ExecutionContext付きpersistはscopeとlegalEntityとasOfを保存する() {
        String traceId = "gw-meta-" + UUID.randomUUID().toString().substring(0, 28);
        CopilotExecutionContext context = matchingContext("default", 77L);

        gateway.execute(AiGatewayRequest.builder()
                .useCase(AiGatewayRequest.USE_MATCHING)
                .traceId(traceId)
                .allowlistedFields(Map.of("engineer.initialName", "A.B"))
                .executionContext(context)
                .scopeContext(context.scope())
                .scopeHash(context.scopeHash())
                .resourceBearing(true)
                .persistRun(true)
                .requireJson(false)
                .build());

        AiRecommendationRun run = runMapper.selectOne(new LambdaQueryWrapper<AiRecommendationRun>()
                .eq(AiRecommendationRun::getTraceId, traceId)
                .last("LIMIT 1"));
        assertNotNull(run);
        assertEquals("default", run.getTenantId());
        assertEquals(77L, run.getLegalEntityId());
        assertEquals(context.scopeHash(), run.getScopeHash());
        assertNotNull(run.getAsOfAt());
        assertEquals("Asia/Tokyo", run.getTimezoneId());
        assertNotNull(run.getCatalogVersion());
        assertNotNull(run.getDataVersion());
    }

    @Test
    void resourceBearingでcontext欠落はfailClosedし不完全行を残さない() {
        long before = countMetaRuns();
        BusinessException ex = assertThrows(BusinessException.class, () ->
                gateway.execute(AiGatewayRequest.builder()
                        .useCase(AiGatewayRequest.USE_MATCHING)
                        .traceId("gw-meta-" + UUID.randomUUID().toString().substring(0, 28))
                        .allowlistedFields(Map.of("engineer.initialName", "A.B"))
                        .resourceBearing(true)
                        .persistRun(true)
                        .requireJson(false)
                        .build()));
        assertEquals(403, ex.getCode());
        assertEquals(before, countMetaRuns());
    }

    @Test
    void 双tenantは交差せずcontextのtenantとHolder不一致は拒否する() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        CopilotExecutionContext foreign = matchingContext("tenant-b", 88L);
        BusinessException ex = assertThrows(BusinessException.class, () ->
                gateway.execute(AiGatewayRequest.builder()
                        .useCase(AiGatewayRequest.USE_MATCHING)
                        .allowlistedFields(Map.of("engineer.initialName", "X.Y"))
                        .executionContext(foreign)
                        .scopeContext(foreign.scope())
                        .scopeHash(foreign.scopeHash())
                        .resourceBearing(true)
                        .persistRun(true)
                        .requireJson(false)
                        .build()));
        assertEquals(403, ex.getCode());
    }

    @Test
    void V161とV162でtenant幅は100に揃いV182は不要() throws Exception {
        String v161 = Files.readString(Path.of(
                "src/main/resources/db/migration/V161__nf08_ai_recommendation_scope_metadata.sql"),
                StandardCharsets.UTF_8);
        String v162 = Files.readString(Path.of(
                "src/main/resources/db/migration/V162__nf02_nf03_boundary_repair.sql"),
                StandardCharsets.UTF_8);
        String h2 = Files.readString(Path.of("src/test/resources/sql/schema-ai-feedback-h2.sql"),
                StandardCharsets.UTF_8);
        assertTrue(v161.contains("tenant_id VARCHAR(64)"));
        assertTrue(v162.contains("MODIFY COLUMN tenant_id VARCHAR(100) NULL"));
        assertTrue(h2.contains("tenant_id VARCHAR(100)"));
        assertFalse(Files.exists(Path.of(
                "src/main/resources/db/migration/V182__ai_recommendation_run_tenant_width.sql")));
    }

    private long countMetaRuns() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_ai_recommendation_run WHERE trace_id LIKE 'gw-meta-%'",
                Long.class);
        return count == null ? 0L : count;
    }

    private static CopilotExecutionContext matchingContext(String tenantId, long legalEntityId) {
        LocalDate asOf = LocalDate.of(2026, 9, 1);
        CopilotExecutionContext context = new CopilotExecutionContext(
                tenantId, legalEntityId, Instant.parse("2026-09-01T00:00:00Z"),
                ZoneId.of("Asia/Tokyo"));
        EffectiveScopeSnapshot snapshot = new EffectiveScopeSnapshot(
                tenantId, legalEntityId, asOf, "COMPANY_WIDE",
                true, false, false, null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                scopeHash(tenantId, legalEntityId, asOf));
        context.bindSnapshot(snapshot);
        context.bind(AiGatewayRequest.USE_MATCHING,
                CopilotQueryParameters.ofQuery(AiGatewayRequest.USE_MATCHING), snapshot.scope());
        return context;
    }

    private static String scopeHash(String tenantId, long legalEntityId, LocalDate asOf) {
        String canonical = "tenant=" + tenantId + "|legalEntity=" + legalEntityId
                + "|asOf=" + asOf + "|scopeType=COMPANY_WIDE|policy="
                + EffectiveScopeSnapshotFactory.POLICY_VERSION + "|members=ALL";
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
