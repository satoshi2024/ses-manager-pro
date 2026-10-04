package com.ses.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import com.ses.service.ai.impl.AiExecutionGatewayImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiExecutionGatewayCopilotSnapshotTest {

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
        return AiGatewayRequest.builder()
                .useCase(AiGatewayRequest.USE_COPILOT)
                .taskMarker(CopilotSummaryPromptTemplate.TASK_MARKER)
                .trustedInstruction(CopilotSummaryPromptTemplate.TRUSTED_INSTRUCTION)
                .allowlistedFields(Map.of("summary.claimKeys", "forecast.utilization.2026-09"))
                .persistRun(false)
                .deadlineNanos(System.nanoTime() + 10_000_000_000L)
                .costBudgetYen(0)
                .expectedArtifactVersionId(7L)
                .expectedProvider("mock")
                .expectedModel("mock-management-copilot")
                .expectedPromptVersion(promptVersion)
                .expectedConfigHash(configHash)
                .build();
    }
}
