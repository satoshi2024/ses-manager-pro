package com.ses.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import com.ses.service.ai.copilot.CopilotInvocation;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import com.ses.service.ai.copilot.summary.CopilotCostState;
import com.ses.service.ai.impl.AiExecutionGatewayImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiExecutionGatewayCopilotSnapshotTest {

    @Test
    void localCopilot成功時はstartupSnapshot付き新overloadと同じprobe経路を使う() {
        AiConfig config = new AiConfig();
        config.setProvider("mock");
        config.setMaxTokens(192);
        AtomicReference<CopilotInvocation> receivedInvocation = new AtomicReference<>();
        AtomicInteger deadlineCalls = new AtomicInteger();
        AtomicInteger legacyCalls = new AtomicInteger();
        CopilotBoundedAiTextService adapter = new CopilotBoundedAiTextService() {
            @Override public String providerId() { return "mock"; }
            @Override public String copilotModelId() { return "mock-management-copilot"; }
            @Override public String copilotPromptVersion() { return CopilotSummaryPromptTemplate.VERSION; }
            @Override public String copilotConfigHash() {
                return CopilotSummaryPromptTemplate.localConfigHash("mock", "mock-management-copilot");
            }
            @Override public String generate(String prompt) {
                legacyCalls.incrementAndGet();
                return "legacy";
            }
            @Override public String generateCopilotSummary(String prompt, long deadlineNanos) {
                deadlineCalls.incrementAndGet();
                return "local-summary";
            }
            @Override public String generateCopilotSummary(String prompt, CopilotInvocation invocation) {
                receivedInvocation.set(invocation);
                return CopilotBoundedAiTextService.super.generateCopilotSummary(prompt, invocation);
            }
        };
        AiOutboundProbe probe = mock(AiOutboundProbe.class);
        AiExecutionGatewayImpl gateway = new AiExecutionGatewayImpl(
                adapter, config, probe, new ObjectMapper(), mock(AiArtifactVersionMapper.class),
                mock(AiRecommendationRunMapper.class), mock(PlatformTransactionManager.class), Clock.systemUTC(),
                mock(AiProductionApprovalGate.class), mock(CopilotFeatureGate.class));
        String expectedConfigHash = adapter.copilotConfigHash();
        config.setProvider("gemini");
        config.setMaxTokens(1);

        CopilotExecutionContext context = copilotContext();
        AiGatewayRequest request = AiGatewayRequest.builder()
                .useCase(AiGatewayRequest.USE_COPILOT)
                .taskMarker(CopilotSummaryPromptTemplate.TASK_MARKER)
                .trustedInstruction(CopilotSummaryPromptTemplate.TRUSTED_INSTRUCTION)
                .allowlistedFields(Map.of("summary.claimKeys", "forecast.utilization.2026-09"))
                .persistRun(false)
                .executionContext(context)
                .scopeContext(context.scope())
                .scopeHash("nf08-local-scope-hash")
                .deadlineNanos(System.nanoTime() + 10_000_000_000L)
                .costBudgetYen(0)
                .expectedArtifactVersionId(7L)
                .expectedProvider("mock")
                .expectedModel("mock-management-copilot")
                .expectedPromptVersion(CopilotSummaryPromptTemplate.VERSION)
                .expectedConfigHash(expectedConfigHash)
                .build();

        String result = AccountingTenantContextHolder.runWithTenant("tenant-a", () -> gateway.execute(request).getText());

        assertEquals("local-summary", result);
        CopilotInvocation invocation = receivedInvocation.get();
        org.junit.jupiter.api.Assertions.assertNotNull(invocation);
        assertEquals("mock", invocation.configSnapshot().configuredProvider());
        assertEquals(192, invocation.configSnapshot().maxOutputTokens());
        assertEquals(expectedConfigHash, invocation.configSnapshot().configHash());
        assertEquals(expectedConfigHash, invocation.preflightCostEvidence().configIdentity());
        assertEquals(0, invocation.preflightCostEvidence().upperBoundYen());
        assertEquals(CopilotCostState.NOT_SENT, invocation.preflightCostEvidence().costState());
        assertEquals(1, deadlineCalls.get());
        assertEquals(0, legacyCalls.get());
        verify(probe, times(1)).record(any());
    }

    @Test
    void remoteCopilotは承認flagや予算額にかかわらず送信前拒否する() {
        CopilotBoundedAiTextService adapter = mock(CopilotBoundedAiTextService.class);
        when(adapter.providerId()).thenReturn("gemini");
        when(adapter.copilotModelId()).thenReturn("gemini-synthetic");
        when(adapter.copilotPromptVersion()).thenReturn("prompt-v1");
        when(adapter.copilotConfigHash()).thenReturn("config-hash-v1");
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setExternalSendEnabled(true);
        config.getProductionGates().setOwnerApproved(true);
        config.getProductionGates().setApprovedCatalog(true);
        config.getProductionGates().setAllowedRoles(true);
        config.getProductionGates().setProviderContract(true);
        config.getProductionGates().setNf07Approved(true);
        config.getProductionGates().setDg08Approved(true);
        config.getProductionGates().setExistingAiProductionGate(true);
        config.getProductionGates().setRetentionApproved(true);
        config.getProductionGates().setCostLimitApproved(true);
        config.getProductionGates().setHumanEscalation(true);
        AiOutboundProbe probe = mock(AiOutboundProbe.class);
        AiExecutionGatewayImpl gateway = new AiExecutionGatewayImpl(
                adapter, config, probe, new ObjectMapper(), mock(AiArtifactVersionMapper.class),
                mock(AiRecommendationRunMapper.class), mock(PlatformTransactionManager.class), Clock.systemUTC(),
                mock(AiProductionApprovalGate.class), mock(CopilotFeatureGate.class));
        config.setProvider("mock");

        BusinessException error = AccountingTenantContextHolder.runWithTenant("tenant-a", () ->
                assertThrows(BusinessException.class, () -> gateway.execute(request(
                        "prompt-v1", "config-hash-v1", "gemini", Integer.MAX_VALUE))));

        assertEquals(503, error.getCode());
        assertEquals("COPILOT_COST_UNKNOWN", error.getMessage());
        verify(adapter, never()).generateCopilotSummary(any(), any(CopilotInvocation.class));
        verify(adapter, never()).generate(any());
        verify(probe, never()).record(any());
    }

    @Test
    void snapshotはendpointのquery内secretを保持しない() {
        AiConfig config = new AiConfig();
        config.setProvider("mock");
        config.setApiUrl("https://example.test/path?key=never-print-this");
        config.setMaxTokens(192);
        CopilotBoundedAiTextService adapter = mock(CopilotBoundedAiTextService.class);
        when(adapter.providerId()).thenReturn("mock");
        when(adapter.copilotModelId()).thenReturn("mock-management-copilot");
        when(adapter.copilotPromptVersion()).thenReturn(CopilotSummaryPromptTemplate.VERSION);
        when(adapter.copilotConfigHash()).thenReturn(
                CopilotSummaryPromptTemplate.localConfigHash("mock", "mock-management-copilot"));

        CopilotConfigSnapshot snapshot = CopilotConfigSnapshot.capture(config, adapter);
        config.setProvider("gemini");
        config.setMaxTokens(1);

        org.junit.jupiter.api.Assertions.assertEquals("mock", snapshot.configuredProvider());
        org.junit.jupiter.api.Assertions.assertEquals(192, snapshot.maxOutputTokens());
        org.junit.jupiter.api.Assertions.assertEquals("UNSAFE_ENDPOINT", snapshot.canonicalEndpoint());
        org.junit.jupiter.api.Assertions.assertFalse(snapshot.toString().contains("never-print-this"));
    }

    @Test
    void nonemptyだが異なるpromptVersionとconfigHashは送信前に拒否する() {
        CopilotBoundedAiTextService adapter = mock(CopilotBoundedAiTextService.class);
        when(adapter.providerId()).thenReturn("mock");
        when(adapter.copilotModelId()).thenReturn("mock-management-copilot");
        when(adapter.copilotPromptVersion()).thenReturn(CopilotSummaryPromptTemplate.VERSION);
        when(adapter.copilotConfigHash()).thenReturn(
                CopilotSummaryPromptTemplate.localConfigHash("mock", "mock-management-copilot"));
        AiConfig config = new AiConfig();
        config.setProvider("mock");
        AiOutboundProbe probe = mock(AiOutboundProbe.class);
        AiExecutionGatewayImpl gateway = new AiExecutionGatewayImpl(
                adapter, config, probe, new ObjectMapper(),
                mock(AiArtifactVersionMapper.class), mock(AiRecommendationRunMapper.class),
                mock(PlatformTransactionManager.class), Clock.systemUTC(),
                mock(AiProductionApprovalGate.class), mock(CopilotFeatureGate.class));

        // gateway起動後のAiConfig変更でartifact照合対象が差し替わらない。
        config.setProvider("gemini");

        AiGatewayRequest[] requests = {
                request("nf08-f1-tampered", adapter.copilotConfigHash()),
                request(CopilotSummaryPromptTemplate.VERSION, "abcdef0123456789")};
        String[] reasons = {"COPILOT_PROMPT_VERSION_MISMATCH", "COPILOT_CONFIG_HASH_MISMATCH"};
        for (int i = 0; i < requests.length; i++) {
            AiGatewayRequest request = requests[i];
            BusinessException error = AccountingTenantContextHolder.runWithTenant("tenant-a", () ->
                    assertThrows(BusinessException.class, () -> gateway.execute(request)));
            assertEquals(503, error.getCode());
            assertEquals(reasons[i], error.getMessage());
        }

        verify(adapter, never()).generateCopilotSummary(any(), anyLong());
        verify(adapter, never()).generate(any());
        verify(probe, never()).record(any());
    }

    private AiGatewayRequest request(String promptVersion, String configHash) {
        return request(promptVersion, configHash, "mock", 0);
    }

    private AiGatewayRequest request(String promptVersion, String configHash, String provider, int budget) {
        return AiGatewayRequest.builder()
                .useCase(AiGatewayRequest.USE_COPILOT)
                .taskMarker(CopilotSummaryPromptTemplate.TASK_MARKER)
                .trustedInstruction(CopilotSummaryPromptTemplate.TRUSTED_INSTRUCTION)
                .allowlistedFields(Map.of("summary.claimKeys", "forecast.utilization.2026-09"))
                .persistRun(false)
                .deadlineNanos(System.nanoTime() + 10_000_000_000L)
                .costBudgetYen(budget)
                .expectedArtifactVersionId(7L)
                .expectedProvider(provider)
                .expectedModel(provider.equals("gemini") ? "gemini-synthetic" : "mock-management-copilot")
                .expectedPromptVersion(promptVersion)
                .expectedConfigHash(configHash)
                .build();
    }

    private CopilotExecutionContext copilotContext() {
        CopilotExecutionContext context = new CopilotExecutionContext("tenant-a", 1L,
                Instant.parse("2026-09-08T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        EffectiveScopeSnapshot snapshot = new EffectiveScopeSnapshot("tenant-a", 1L,
                LocalDate.of(2026, 9, 8), "COMPANY_WIDE", true, false, false,
                null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                scopeHash("tenant-a", 1L, LocalDate.of(2026, 9, 8)));
        context.bindSnapshot(snapshot);
        context.bind("dashboard.summary", CopilotQueryParameters.ofQuery("dashboard.summary"), snapshot.scope());
        return context;
    }

    private static String scopeHash(String tenantId, long legalEntityId, LocalDate asOf) {
        String canonical = "tenant=" + tenantId + "|legalEntity=" + legalEntityId
                + "|asOf=" + asOf + "|scopeType=COMPANY_WIDE|policy="
                + EffectiveScopeSnapshotFactory.POLICY_VERSION + "|members=ALL";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("effective scope hash calculation failed", ex);
        }
    }

}
