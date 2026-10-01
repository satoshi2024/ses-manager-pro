package com.ses.controller.api;

import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
import com.ses.service.ai.LegacyAiEndpointBoundary;
import com.ses.service.security.DataScopeService;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF08: resource-bearing legacy chatは認可時とgatewayで同じsnapshotを使う。 */
@ExtendWith(MockitoExtension.class)
class AiRestControllerContextPropagationTest {
    @Mock AiExecutionGateway gateway;
    @Mock EngineerService engineerService;
    @Mock ProjectService projectService;
    @Mock DataScopeService dataScopeService;
    @Mock LegacyAiEndpointBoundary boundary;

    @Test
    void resourceBearingChatは同じExecutionContextとscopeHashをgatewayへ渡す() {
        Engineer engineer = new Engineer();
        engineer.setId(1L);
        engineer.setLegalEntityId(77L);
        CopilotExecutionContext context = context();
        when(boundary.createContext()).thenReturn(context);
        when(boundary.assertEngineer(1L, context)).thenReturn(engineer);
        when(gateway.execute(any())).thenReturn(new AiGatewayResult("ok", "trace", null, "prompt"));

        AiRestController controller = new AiRestController(gateway, engineerService, projectService,
                dataScopeService, boundary);
        AiRestController.AiChatRequest request = new AiRestController.AiChatRequest();
        request.setEngineerId(1L);
        request.setPrompt("説明");

        controller.chat(request);

        ArgumentCaptor<AiGatewayRequest> captor = ArgumentCaptor.forClass(AiGatewayRequest.class);
        verify(gateway).execute(captor.capture());
        AiGatewayRequest gatewayRequest = captor.getValue();
        assertEquals(context, gatewayRequest.getExecutionContext());
        assertEquals(context.scope(), gatewayRequest.getScopeContext());
        assertEquals(context.scopeHash(), gatewayRequest.getScopeHash());
        assertTrue(gatewayRequest.isResourceBearing());
    }

    @Test
    void resource無しchatも統一gateを通しcreateContextは呼ばない() {
        when(gateway.execute(any())).thenReturn(new AiGatewayResult("ok", "trace", null, "prompt"));

        AiRestController controller = new AiRestController(gateway, engineerService, projectService,
                dataScopeService, boundary);
        AiRestController.AiChatRequest request = new AiRestController.AiChatRequest();
        request.setPrompt("こんにちは");

        controller.chat(request);

        verify(boundary).assertEndpointAllowed();
        ArgumentCaptor<AiGatewayRequest> captor = ArgumentCaptor.forClass(AiGatewayRequest.class);
        verify(gateway).execute(captor.capture());
        assertNull(captor.getValue().getExecutionContext());
        org.mockito.Mockito.verify(boundary, org.mockito.Mockito.never()).createContext();
    }

    private static CopilotExecutionContext context() {
        CopilotExecutionContext context = new CopilotExecutionContext("tenant-e1", 77L,
                Instant.parse("2026-03-01T00:00:00Z"), ZoneId.of("UTC"));
        context.bind("legacy.ai", CopilotQueryParameters.ofQuery("legacy.ai"),
                new CopilotScopeContext("COMPANY_WIDE", "nf08-legacy-scope-1", "legacy-hash",
                        false, "tenant-e1", 77L, "ALL"));
        return context;
    }
}
