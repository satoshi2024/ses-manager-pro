package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NF08: legacy入口はmanagement-copilot flagではなくai.enabledとproduction gateで統一する。 */
class LegacyAiEndpointFeatureGateTest {

    @Test
    void aiEnabledFalseはlegacy入口を503で拒否する() {
        AiConfig config = new AiConfig();
        config.setEnabled(false);
        config.setProvider("mock");
        config.setExternalSendEnabled(false);
        CopilotFeatureGate gate = new CopilotFeatureGate(config);

        BusinessException ex = assertThrows(BusinessException.class, gate::assertLegacyEndpointAllowed);
        assertEquals(503, ex.getCode());
    }

    @Test
    void mockとexternalSendFalseはlegacy入口を許可する() {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setProvider("mock");
        config.setExternalSendEnabled(false);
        CopilotFeatureGate gate = new CopilotFeatureGate(config);

        assertDoesNotThrow(gate::assertLegacyEndpointAllowed);
    }

    @Test
    void geminiとexternalSendFalseはlegacy入口でもproviderを拒否する() {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setProvider("gemini");
        config.setExternalSendEnabled(false);
        CopilotFeatureGate gate = new CopilotFeatureGate(config);

        BusinessException ex = assertThrows(BusinessException.class, gate::assertLegacyEndpointAllowed);
        assertEquals(503, ex.getCode());
    }

    @Test
    void managementCopilot無効でもlegacy入口はaiEnabledだけで判定する() {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setManagementCopilotEnabled(false);
        config.setProvider("mock");
        config.setExternalSendEnabled(false);
        CopilotFeatureGate gate = new CopilotFeatureGate(config);

        assertDoesNotThrow(gate::assertLegacyEndpointAllowed);
        assertThrows(BusinessException.class, gate::assertQueryAllowed);
    }
}
