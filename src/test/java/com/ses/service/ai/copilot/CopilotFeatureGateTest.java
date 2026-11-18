package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CopilotFeatureGateTest {

    @Autowired
    private AiConfig aiConfig;

    @Test
    void 本番flagは既定OFFで外部送信も禁止() {
        assertFalse(aiConfig.isManagementCopilotEnabled());
        assertFalse(aiConfig.isExternalSendEnabled());
        assertFalse(aiConfig.isEnabled());
    }

    @Test
    void externalSend有効化はqueryを拒否する() {
        AiConfig config = mock(AiConfig.class);
        when(config.isEnabled()).thenReturn(true);
        when(config.isManagementCopilotEnabled()).thenReturn(true);
        when(config.isExternalSendEnabled()).thenReturn(true);

        var contextFactory = mock(CopilotExecutionContextFactory.class);
        CopilotQueryService service = new CopilotQueryService(
                config,
                mock(IntentParser.class),
                mock(com.ses.service.ai.copilot.parameter.TypedParameterBinder.class),
                mock(com.ses.service.ai.copilot.scope.CopilotScopeResolver.class),
                mock(com.ses.service.ai.copilot.gateway.CatalogQueryGateway.class),
                mock(CopilotRunService.class),
                mock(com.ses.service.ai.copilot.citation.CitationAuthorizationService.class),
                mock(com.ses.service.ai.copilot.summary.CopilotSummaryService.class),
                contextFactory,
                new CopilotFeatureGate(config));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.query("稼働率"));
        assertEquals(503, ex.getCode());
    }

    @Test
    void managementCopilotが無効なら全ての推論入口を拒否する() {
        AiConfig config = new AiConfig();
        config.setManagementCopilotEnabled(false);
        config.setExternalSendEnabled(false);
        CopilotFeatureGate gate = new CopilotFeatureGate(config);

        assertThrows(BusinessException.class, gate::assertQueryAllowed);
        assertThrows(BusinessException.class, gate::assertCitationAllowed);
        assertThrows(BusinessException.class, gate::assertEvaluationAllowed);
        assertThrows(BusinessException.class, gate::assertExportAllowed);
    }

    @Test
    void 外部送信が無効でも非localProviderはfailClosedする() {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setManagementCopilotEnabled(true);
        config.setExternalSendEnabled(false);
        config.setProvider("gemini");
        CopilotFeatureGate gate = new CopilotFeatureGate(config);

        assertThrows(BusinessException.class, gate::assertQueryAllowed);
    }

    @Test
    void providerの未設定と未知値はfailClosedする() {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setManagementCopilotEnabled(true);
        config.setExternalSendEnabled(false);
        CopilotFeatureGate gate = new CopilotFeatureGate(config);

        config.setProvider(null);
        assertThrows(BusinessException.class, gate::assertQueryAllowed);
        config.setProvider("unknown-provider");
        assertThrows(BusinessException.class, gate::assertQueryAllowed);
    }

    @Test
    void 独立gate設定があっても未知providerはfailClosedする() {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setManagementCopilotEnabled(true);
        config.setExternalSendEnabled(false);
        config.setProvider("unregistered-provider");
        CopilotFeatureGate gate = new CopilotFeatureGate(config);

        assertThrows(BusinessException.class, gate::assertQueryAllowed);
    }

    @Test
    void 承認が失効しても保存期間のpurgeは止めず推論は拒否する() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 13);
        AiConfig config = com.ses.service.ai.AiExternalSendApprovalFixtures.approvedConfig(today);
        config.getExternalSendApproval().setExpiresOn(today.plusDays(1).toString());
        com.ses.service.ai.AiExternalSendApprovalFixtures.resign(config);
        // 2026-11-14 10:00 JST（失効日当日）。失効しても起動は継続するため、purgeまで止めると保存期間を超える。
        java.time.Clock expired = java.time.Clock.fixed(java.time.Instant.parse("2026-11-14T01:00:00Z"),
                java.time.ZoneId.of("UTC"));
        CopilotFeatureGate gate = new CopilotFeatureGate(config,
                new com.ses.service.ai.AiProductionApprovalGate(config, expired));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(gate::assertRetentionMaintenanceAllowed);
        assertThrows(BusinessException.class, gate::assertQueryAllowed);
    }

    @Test
    void 承認設定に構造的な不足があるexternalSendはpurgeもfailClosedする() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 13);
        AiConfig config = com.ses.service.ai.AiExternalSendApprovalFixtures.approvedConfig(today);
        config.getExternalSendApproval().setDecisionId(null);
        CopilotFeatureGate gate = new CopilotFeatureGate(config, new com.ses.service.ai.AiProductionApprovalGate(
                config, java.time.Clock.fixed(java.time.Instant.parse("2026-11-13T01:00:00Z"),
                        java.time.ZoneId.of("UTC"))));

        BusinessException ex = assertThrows(BusinessException.class, gate::assertRetentionMaintenanceAllowed);
        assertEquals(503, ex.getCode());
    }
}
