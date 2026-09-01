package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
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
        when(config.isManagementCopilotEnabled()).thenReturn(true);
        when(config.isExternalSendEnabled()).thenReturn(true);

        CopilotQueryService service = new CopilotQueryService(
                config,
                mock(IntentParser.class),
                mock(com.ses.service.ai.copilot.parameter.TypedParameterBinder.class),
                mock(com.ses.service.ai.copilot.scope.CopilotScopeResolver.class),
                mock(com.ses.service.ai.copilot.gateway.CatalogQueryGateway.class),
                mock(CopilotRunService.class),
                mock(com.ses.service.ai.copilot.citation.CitationAuthorizationService.class),
                mock(com.ses.service.ai.copilot.summary.CopilotSummaryService.class));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.query("稼働率"));
        assertEquals(503, ex.getCode());
    }
}
