package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.AiConfig;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.ai.impl.AiExecutionGatewayImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF08: ingestion/learningはlegacyの暗黙local policyへ吸収せず、用途別に明示する。 */
@ExtendWith(MockitoExtension.class)
class AiUseCasePolicyBoundaryTest {
    @Mock AiTextService aiTextService;
    @Mock AiOutboundProbe outboundProbe;
    @Mock AiArtifactVersionMapper versionMapper;
    @Mock AiRecommendationRunMapper runMapper;
    @Mock PlatformTransactionManager transactionManager;

    private static final List<String> OFFLINE_USE_CASES = List.of(
            AiGatewayRequest.USE_INGEST_RESUME,
            AiGatewayRequest.USE_INGEST_PROJECT,
            AiGatewayRequest.USE_INGEST_BP,
            AiGatewayRequest.USE_LEARNING_CANDIDATE);

    @Test
    void ingestionとlearningはmock_ruleだけを明示的に許可する() {
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("default");
        try {
            AiConfig config = config("mock");
            AiExecutionGateway gateway = gateway(config);
            when(aiTextService.generate(anyString())).thenReturn("local");

            for (String provider : List.of("mock", "rule")) {
                config.setProvider(provider);
                for (String useCase : OFFLINE_USE_CASES) {
                    AiGatewayResult result = gateway.execute(AiGatewayRequest.builder()
                            .useCase(useCase)
                            .persistRun(false)
                            .build());
                    assertEquals("local", result.getText(), provider + ":" + useCase);
                }
            }

            verify(aiTextService, org.mockito.Mockito.times(OFFLINE_USE_CASES.size() * 2))
                    .generate(anyString());
        } finally {
            com.ses.service.accounting.AccountingTenantContextHolder.clear();
        }
    }

    @Test
    void ingestionとlearningはexternalDisabledおよびunknownProviderをfailClosedする() {
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("default");
        try {
            AiConfig config = config("gemini");
            AiExecutionGateway gateway = gateway(config);

            for (String useCase : OFFLINE_USE_CASES) {
                BusinessException externalDisabled = assertThrows(BusinessException.class,
                        () -> gateway.execute(AiGatewayRequest.builder()
                                .useCase(useCase).persistRun(false).build()));
                assertEquals(503, externalDisabled.getCode(), useCase);
            }

            config.setProvider("unknown-provider");
            for (String useCase : OFFLINE_USE_CASES) {
                BusinessException unknown = assertThrows(BusinessException.class,
                        () -> gateway.execute(AiGatewayRequest.builder()
                                .useCase(useCase).persistRun(false).build()));
                assertEquals(503, unknown.getCode(), useCase);
            }

            verify(aiTextService, never()).generate(anyString());
        } finally {
            com.ses.service.accounting.AccountingTenantContextHolder.clear();
        }
    }

    @Test
    void 未登録useCaseはproviderのlocal設定に関係なく拒否する() {
        AiConfig config = config("mock");
        AiExecutionGateway gateway = gateway(config);

        assertThrows(BusinessException.class, () -> gateway.execute(AiGatewayRequest.builder()
                .useCase("TYPO_INGEST_RESUME")
                .persistRun(false)
                .build()));

        verify(aiTextService, never()).generate(anyString());
    }

    private AiExecutionGateway gateway(AiConfig config) {
        return new AiExecutionGatewayImpl(aiTextService, config, outboundProbe,
                new ObjectMapper(), versionMapper, runMapper, transactionManager,
                Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC),
                new AiProductionApprovalGate(config), new CopilotFeatureGate(config));
    }

    private static AiConfig config(String provider) {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setProvider(provider);
        config.setExternalSendEnabled(false);
        return config;
    }
}
