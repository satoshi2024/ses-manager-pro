package com.ses.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.ai.impl.AiExecutionGatewayImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** NF08: legacy use caseはproduction approvalが揃っていてもreal providerへ昇格しない。 */
@ExtendWith(MockitoExtension.class)
class AiExecutionGatewayLegacyBoundaryTest {
    @Mock AiTextService aiTextService;
    @Mock AiOutboundProbe outboundProbe;
    @Mock AiArtifactVersionMapper versionMapper;
    @Mock AiRecommendationRunMapper runMapper;
    @Mock PlatformTransactionManager transactionManager;

    @Test
    void geminiと全productionGateがtrueでもlegacy三用途はgenerateしない() {
        AiConfig config = config("gemini", true);
        AiExecutionGateway gateway = gateway(config);

        for (String provider : List.of("gemini", "openai", "other")) {
            config.setProvider(provider);
            for (String useCase : List.of(AiGatewayRequest.USE_CHAT,
                    AiGatewayRequest.USE_MATCHING, AiGatewayRequest.USE_PROPOSAL_DRAFT)) {
                AiGatewayRequest.AiGatewayRequestBuilder builder = AiGatewayRequest.builder()
                        .useCase(useCase)
                        .persistRun(false)
                        .requireJson(false);
                if (!AiGatewayRequest.USE_CHAT.equals(useCase)) {
                    CopilotExecutionContext context = context();
                    builder.executionContext(context)
                            .scopeContext(context.scope())
                            .scopeHash(context.scopeHash())
                            .resourceBearing(true);
                }

                assertThrows(BusinessException.class, () -> gateway.execute(builder.build()),
                        provider + ":" + useCase);
            }
        }

        verify(aiTextService, never()).generate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resourceBearingLegacyはcontextなし又はscope不一致を拒否する() {
        AiConfig config = config("mock", false);
        AiExecutionGateway gateway = gateway(config);
        assertEquals(403, assertThrows(BusinessException.class, () -> gateway.execute(
                AiGatewayRequest.builder().useCase(AiGatewayRequest.USE_CHAT)
                        .resourceBearing(true).build())).getCode());

        CopilotExecutionContext context = context();
        assertEquals(403, assertThrows(BusinessException.class, () -> gateway.execute(
                AiGatewayRequest.builder().useCase(AiGatewayRequest.USE_PROPOSAL_DRAFT)
                        .executionContext(context).scopeContext(context.scope())
                        .scopeHash("different").resourceBearing(true).build())).getCode());
        verify(aiTextService, never()).generate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void chatのtypedResourceはresourceBearingFalseでもcontext必須() {
        AiConfig config = config("mock", false);
        AiExecutionGateway gateway = gateway(config);

        assertEquals(403, assertThrows(BusinessException.class, () -> gateway.execute(
                AiGatewayRequest.builder().useCase(AiGatewayRequest.USE_CHAT)
                        .engineerId(101L)
                        .resourceBearing(false)
                        .build())).getCode());

        assertEquals(403, assertThrows(BusinessException.class, () -> gateway.execute(
                AiGatewayRequest.builder().useCase(AiGatewayRequest.USE_CHAT)
                        .allowlistedFields(java.util.Map.of("engineer.initialName", "E1"))
                        .resourceBearing(false)
                        .build())).getCode());

        assertEquals(403, assertThrows(BusinessException.class, () -> gateway.execute(
                AiGatewayRequest.legacyMatching(null).resourceBearing(false).build())).getCode());

        verify(aiTextService, never()).generate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void mockとruleのresourceなしlegacyはlocalProviderとして継続する() {
        AiConfig config = config("mock", false);
        AiExecutionGateway gateway = gateway(config);
        org.mockito.Mockito.when(aiTextService.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("local");
        assertEquals("local", gateway.execute(AiGatewayRequest.builder()
                .useCase(AiGatewayRequest.USE_CHAT).build()).getText());

        config.setProvider("rule");
        assertEquals("local", gateway.execute(AiGatewayRequest.builder()
                .useCase(AiGatewayRequest.USE_CHAT).build()).getText());
        verify(aiTextService, org.mockito.Mockito.times(2))
                .generate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void managementCopilotのunknownProviderは全gateがtrueでもgenerateしない() {
        AiConfig config = config("unknown", true);
        AiExecutionGateway gateway = gateway(config);
        CopilotExecutionContext context = context();
        assertThrows(BusinessException.class, () -> gateway.execute(
                AiGatewayRequest.builder().useCase(AiGatewayRequest.USE_COPILOT)
                        .executionContext(context).scopeContext(context.scope())
                        .scopeHash(context.scopeHash()).build()));
        verify(aiTextService, never()).generate(org.mockito.ArgumentMatchers.anyString());
    }

    private AiExecutionGateway gateway(AiConfig config) {
        return new AiExecutionGatewayImpl(aiTextService, config, outboundProbe,
                new ObjectMapper(), versionMapper, runMapper, transactionManager,
                Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneId.of("UTC")),
                new AiProductionApprovalGate(config), new CopilotFeatureGate(config));
    }

    private static AiConfig config(String provider, boolean externalSend) {
        AiConfig config = new AiConfig();
        config.setProvider(provider);
        config.setEnabled(true);
        config.setManagementCopilotEnabled(true);
        config.setExternalSendEnabled(externalSend);
        AiConfig.ProductionGates gates = new AiConfig.ProductionGates();
        gates.setOwnerApproved(true);
        gates.setApprovedCatalog(true);
        gates.setAllowedRoles(true);
        gates.setProviderContract(true);
        gates.setNf07Approved(true);
        gates.setDg08Approved(true);
        gates.setExistingAiProductionGate(true);
        gates.setRetentionApproved(true);
        gates.setCostLimitApproved(true);
        gates.setHumanEscalation(true);
        config.setProductionGates(gates);
        return config;
    }

    private static CopilotExecutionContext context() {
        CopilotExecutionContext context = new CopilotExecutionContext("tenant-e1", 77L,
                Instant.parse("2026-03-01T00:00:00Z"), ZoneId.of("UTC"));
        EffectiveScopeSnapshot snapshot = new EffectiveScopeSnapshot(
                "tenant-e1", 77L, java.time.LocalDate.of(2026, 3, 1), "COMPANY_WIDE",
                true, false, false, null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                "f3dd98b76990431a216c9bd8fcc7ccdefe410b23d53b81a26c02639edd0a498e");
        context.bindSnapshot(snapshot);
        context.bind("legacy.ai", CopilotQueryParameters.ofQuery("legacy.ai"), snapshot.scope());
        return context;
    }
}
