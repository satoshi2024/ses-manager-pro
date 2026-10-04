package com.ses.service.ai.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.copilot.summary.SummaryResponse;
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotRunServiceTest {

    @Mock
    private AiArtifactVersionMapper versionMapper;
    @Mock
    private AiRecommendationRunMapper runMapper;

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void queryとsummaryを別attemptとして同一artifactで記録する() throws Exception {
        AiArtifactVersion active = new AiArtifactVersion();
        active.setId(72L);
        active.setUseCase("MANAGEMENT_COPILOT");
        active.setProvider("mock");
        active.setModelName("mock-management-copilot");
        active.setPromptVersion("nf08-f1");
        active.setConfigHash(CopilotSummaryPromptTemplate.localConfigHash("mock", "mock-management-copilot"));
        active.setRuleVersion("catalog-v1");
        active.setStatus("ACTIVE");
        active.setStatusVersion(4);
        when(versionMapper.selectOne(any())).thenReturn(active);

        AtomicLong ids = new AtomicLong(100L);
        when(runMapper.insert(any(AiRecommendationRun.class))).thenAnswer(invocation -> {
            AiRecommendationRun run = invocation.getArgument(0);
            run.setId(ids.getAndIncrement());
            return 1;
        });

        CopilotRunService service = new CopilotRunService(versionMapper, runMapper, new ObjectMapper());
        CopilotRunService.CopilotArtifactSnapshot artifact = service.pinActiveArtifact();
        SemanticCatalogEntry entry = new SemanticCatalogEntry("dashboard.summary", "catalog-v1", "schema-v1",
                Set.of("管理者"), true, 200, List.of("dashboard.summary"));
        CopilotExecutionContext context = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        CopilotScopeContext scope = new CopilotScopeContext("COMPANY_WIDE", "scope-v1", "scope-hash",
                false, "tenant-a", 10L, "ALL");
        context.bind(entry.queryId(), CopilotQueryParameters.ofQuery(entry.queryId()), scope);

        CopilotRunService.CopilotAttemptRecord[] attempts = new CopilotRunService.CopilotAttemptRecord[2];
        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> {
            CopilotRunService.CopilotAttemptRecord query = service.beginQueryAttempt(
                    entry, "parameter-hash", context, artifact);
            CopilotRunService.CopilotAttemptRecord summary = service.beginSummaryAttempt(
                    entry, "parameter-hash", context, artifact, query);
            attempts[0] = query;
            attempts[1] = summary;
            assertNotEquals(query.traceId(), summary.traceId());
            assertEquals(query.traceId(), summary.parentTraceId());
            assertEquals(artifact, summary.artifact());
            assertEquals("QUERY", query.attemptKind());
            assertEquals("SUMMARY", summary.attemptKind());
        });

        ArgumentCaptor<AiRecommendationRun> captor = ArgumentCaptor.forClass(AiRecommendationRun.class);
        verify(runMapper, times(2)).insert(captor.capture());
        List<AiRecommendationRun> runs = captor.getAllValues();
        assertEquals("PENDING", runs.get(0).getStatus());
        assertEquals("PENDING", runs.get(1).getStatus());
        assertNull(runs.get(0).getLatencyMs());
        assertNull(runs.get(0).getCostJpy());
        assertNull(runs.get(1).getCostJpy());
        assertEquals(72L, runs.get(0).getArtifactVersionId());
        assertEquals(72L, runs.get(1).getArtifactVersionId());
        var queryMetadata = new ObjectMapper().readTree(runs.get(0).getRedactedSummaryJson());
        var summaryMetadata = new ObjectMapper().readTree(runs.get(1).getRedactedSummaryJson());
        assertEquals("NOT_APPLICABLE", queryMetadata.path("costState").asText());
        assertEquals("UNKNOWN", summaryMetadata.path("costState").asText());
        assertEquals(runs.get(0).getTraceId(), summaryMetadata.path("parentTraceId").asText());
        assertEquals(artifact.configHash(), summaryMetadata.path("configHash").asText());
        assertFalse(runs.get(0).getRedactedSummaryJson().contains("raw prompt"));
        assertTrue(runs.get(1).getRedactedSummaryJson().contains("promptVersion"));
        verify(versionMapper, times(1)).selectOne(any());

        CopilotRunService.CopilotArtifactSnapshot driftedArtifact = new CopilotRunService.CopilotArtifactSnapshot(
                73L, artifact.provider(), artifact.modelName(), artifact.promptVersion(), artifact.configHash(),
                artifact.ruleVersion(), artifact.statusVersion());
        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> assertThrows(IllegalArgumentException.class,
                () -> service.beginSummaryAttempt(entry, "parameter-hash", context,
                        driftedArtifact, attempts[0])));
        verify(runMapper, times(2)).insert(any(AiRecommendationRun.class));

        when(runMapper.update(org.mockito.ArgumentMatchers.<AiRecommendationRun>isNull(),
                any(Wrapper.class))).thenReturn(0, 1, 1);
        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> {
            assertThrows(IllegalStateException.class,
                    () -> service.completeQueryAttempt(attempts[0], 23, true, 4));
            service.completeQueryAttempt(attempts[0], 23, true, 4);
            service.completeSummaryAttempt(attempts[1], 47, SummaryResponse.unavailable("COST_UNKNOWN"));
        });

        ArgumentCaptor<Wrapper<AiRecommendationRun>> updateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(runMapper, times(3)).update(org.mockito.ArgumentMatchers.<AiRecommendationRun>isNull(),
                updateCaptor.capture());
        LambdaUpdateWrapper<AiRecommendationRun> queryUpdate =
                (LambdaUpdateWrapper<AiRecommendationRun>) updateCaptor.getAllValues().get(1);
        LambdaUpdateWrapper<AiRecommendationRun> summaryUpdate =
                (LambdaUpdateWrapper<AiRecommendationRun>) updateCaptor.getAllValues().get(2);
        queryUpdate.getSqlSegment();
        summaryUpdate.getSqlSegment();
        assertTrue(queryUpdate.getParamNameValuePairs().containsValue("SUCCEEDED"));
        assertTrue(queryUpdate.getParamNameValuePairs().containsValue(23));
        assertTrue(queryUpdate.getParamNameValuePairs().containsValue("PENDING"));
        assertTrue(queryUpdate.getParamNameValuePairs().containsValue(0));
        assertTrue(summaryUpdate.getParamNameValuePairs().containsValue("FAILED"));
        assertTrue(summaryUpdate.getParamNameValuePairs().containsValue("COST_UNKNOWN"));
        assertTrue(summaryUpdate.getParamNameValuePairs().containsValue(47));
        assertTrue(summaryUpdate.getSqlSet().contains("cost_jpy"));
        assertTrue(summaryUpdate.getParamNameValuePairs().values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(value -> value.contains("\"costState\":\"UNKNOWN\"")));
    }
}
