package com.ses.controller.externalapi;

import com.ses.config.integrationhub.ExternalApiCanonicalRequest;
import com.ses.config.integrationhub.ExternalApiErrorWriter;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiSecurityException;
import com.ses.config.integrationhub.ExternalApiResponseBoundaryFilter;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.config.integrationhub.IntegrationHubInboundProviderCatalog;
import com.ses.dto.integrationhub.ExternalApiInboundWebhookResponse;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.service.integrationhub.ExternalApiInboundWebhookParser;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.InboundEventProcessor;
import com.ses.service.integrationhub.InboundEventService;
import com.ses.service.integrationhub.IntegrationHubStates;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletException;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B2 parserの入力契約から実際のHTTP statusへの対応を固定する。 */
class ExternalApiInboundWebhookControllerContractTest {

    @Test
    void 新規受信は202_duplicateは200_conflictは409を返す() {
        ExternalApiInboundWebhookParser parser = mock(ExternalApiInboundWebhookParser.class);
        InboundEventService events = mock(InboundEventService.class);
        InboundEventProcessor processor = mock(InboundEventProcessor.class);
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        ExternalApiInboundWebhookController controller = new ExternalApiInboundWebhookController(
                parser, events, processor, properties,
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC));
        InboundEvent event = InboundEvent.builder().id(1L).status(IntegrationHubStates.INBOUND_RECEIVED)
                .resultCode("INBOUND_ACCEPTED").version(0).build();
        when(parser.parse(anyString(), anyString(), any(byte[].class), any(LocalDateTime.class)))
                .thenReturn(new ExternalApiInboundWebhookParser.Parsed(
                        "provider-a", "event-1", "health.ping", snapshot()));
        when(events.recordReceived(anyString(), anyString(), anyString(), anyString(), any(LocalDateTime.class),
                any(ExternalDtoSnapshot.class), anyBoolean(), any(LocalDateTime.class)))
                .thenReturn(new InboundEventService.Receipt(event, false, false, false));
        when(events.claim(any(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(null);

        ResponseEntity<ExternalApiInboundWebhookResponse> accepted = controller.receive(
                "provider-a", request("event-1"));
        assertEquals(202, accepted.getStatusCode().value());

        when(events.recordReceived(anyString(), anyString(), anyString(), anyString(), any(LocalDateTime.class),
                any(ExternalDtoSnapshot.class), anyBoolean(), any(LocalDateTime.class)))
                .thenReturn(new InboundEventService.Receipt(event, true, false, false));
        ResponseEntity<ExternalApiInboundWebhookResponse> duplicate = controller.receive(
                "provider-a", request("event-1"));
        assertEquals(200, duplicate.getStatusCode().value());

        when(events.recordReceived(anyString(), anyString(), anyString(), anyString(), any(LocalDateTime.class),
                any(ExternalDtoSnapshot.class), anyBoolean(), any(LocalDateTime.class)))
                .thenReturn(new InboundEventService.Receipt(event, false, true, false));
        ExternalApiSecurityException conflict = assertThrows(ExternalApiSecurityException.class,
                () -> controller.receive("provider-a", request("event-1")));
        assertEquals(409, conflict.getStatus().value());
    }

    @Test
    void 実parserの最小合法requestは202_nested不正は実HTTP400になる() throws Exception {
        ExternalApiInboundWebhookParser parser = new ExternalApiInboundWebhookParser(
                new ObjectMapper(), new IntegrationHubInboundProviderCatalog(Set.of("provider-a")));
        InboundEventService events = mock(InboundEventService.class);
        InboundEventProcessor processor = mock(InboundEventProcessor.class);
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        ExternalApiInboundWebhookController controller = new ExternalApiInboundWebhookController(
                parser, events, processor, properties,
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC));
        InboundEvent event = InboundEvent.builder().id(2L).status(IntegrationHubStates.INBOUND_RECEIVED)
                .resultCode("INBOUND_ACCEPTED").version(0).build();
        when(events.recordReceived(anyString(), anyString(), anyString(), anyString(), any(LocalDateTime.class),
                any(ExternalDtoSnapshot.class), anyBoolean(), any(LocalDateTime.class)))
                .thenReturn(new InboundEventService.Receipt(event, false, false, false));
        when(events.claim(any(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(null);

        MockHttpServletResponse accepted = new MockHttpServletResponse();
        new ExternalApiResponseBoundaryFilter(new ObjectMapper()).doFilter(
                request("event-minimal", "{\"providerEventId\":\"event-minimal\",\"eventType\":\"health.ping\"}"),
                accepted, (request, response) -> invoke(controller, request, response));
        assertEquals(202, accepted.getStatus());

        MockHttpServletResponse invalid = new MockHttpServletResponse();
        new ExternalApiResponseBoundaryFilter(new ObjectMapper()).doFilter(
                request("event-invalid", "{\"providerEventId\":\"event-invalid\",\"eventType\":\"health.ping\","
                        + "\"canonicalPayload\":{\"payload\":{\"internalDatabaseId\":1}}}"),
                invalid, (request, response) -> invoke(controller, request, response));
        assertEquals(400, invalid.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(invalid.getContentAsString().contains("REQUEST_INVALID"));
    }

    private void invoke(ExternalApiInboundWebhookController controller,
                        jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
            throws ServletException {
        try {
            ResponseEntity<?> result = controller.receive("provider-a",
                    (jakarta.servlet.http.HttpServletRequest) request);
            ((jakarta.servlet.http.HttpServletResponse) response).setStatus(result.getStatusCode().value());
        } catch (RuntimeException ex) {
            throw new ServletException(ex);
        }
    }

    private MockHttpServletRequest request(String eventId) {
        return request(eventId, "{}" );
    }

    private MockHttpServletRequest request(String eventId, String rawBody) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/external-api/v1/webhooks/provider-a");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("X-Provider-Event-ID", eventId);
        request.setAttribute(ExternalApiCanonicalRequest.class.getName(),
                new ExternalApiCanonicalRequest.Parsed(
                        "/external-api/v1/webhooks/provider-a", "/external-api/v1/webhooks/provider-a",
                        rawBody.getBytes(StandardCharsets.UTF_8), "hash"));
        request.setAttribute(ExternalApiErrorWriter.PRINCIPAL_ATTRIBUTE,
                new ExternalApiPrincipal("client", 1L, "tenant", 9L, null, 1, "inbound", "INTERNAL_TEST"));
        request.setAttribute(com.ses.config.integrationhub.ExternalApiAuthenticationFilter.SIGNED_TIMESTAMP_ATTRIBUTE,
                LocalDateTime.of(2026, 8, 31, 0, 0));
        return request;
    }

    private ExternalDtoSnapshot snapshot() {
        return ExternalDtoSnapshot.ofAllowList(
                "{\"providerEventId\":\"event-1\",\"provider\":\"provider-a\","
                        + "\"eventType\":\"health.ping\",\"signatureResult\":\"VALID\"}",
                ExternalDtoSnapshot.INBOUND_FIELDS);
    }
}
