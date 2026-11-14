package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.service.integrationhub.ExternalApiAuditRecord;
import com.ses.service.integrationhub.ExternalApiAuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ExternalApiAuditBoundaryTest {
    @Test
    void oneBoundedRecordContainsPrincipalCredentialCorrelationAndAllDecisions() throws Exception {
        ExternalApiAuditService service = mock(ExternalApiAuditService.class);
        ExternalApiMetricsRecorder metrics = mock(ExternalApiMetricsRecorder.class);
        AtomicReference<ExternalApiAuditRecord> captured = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        }).when(service).recordRequired(org.mockito.ArgumentMatchers.any());
        ExternalApiAuditBoundary boundary = boundary(service, metrics);
        MockHttpServletRequest request = request();
        request.setAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE, "corr-123456789012");
        request.setAttribute(ExternalApiErrorWriter.ROUTE_ATTRIBUTE, "/external-api/v1/projects");
        request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "AUTHORIZED");
        ExternalApiPrincipal principal = new ExternalApiPrincipal("client-a", 7L, "tenant-a", 9L,
                "{\"projectIds\":[\"p-1\"]}", 3, "key-3", "STANDARD");
        request.setAttribute(ExternalApiErrorWriter.PRINCIPAL_ATTRIBUTE, principal);
        boundary.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            ExternalApiAuditTrail.correlation(request, "corr-123456789012");
            ExternalApiAuditTrail.route(request, "/external-api/v1/projects");
            ExternalApiAuditTrail.principal(request, principal);
            ExternalApiAuditTrail.mark(request, "authentication", "AUTHENTICATED");
            ExternalApiAuditTrail.mark(request, "scope", "ALLOWED");
            ExternalApiAuditTrail.mark(request, "dataScope", "INTERSECTION_ALLOWED");
            ExternalApiAuditTrail.mark(request, "command", "READ_ALLOWED");
            ExternalApiAuditTrail.mark(request, "rate", "ALLOWED");
        });

        ExternalApiAuditRecord record = captured.get();
        assertNotNull(record);
        assertEquals("UNAUTHENTICATED", record.preAuthPrincipal());
        assertEquals("client-a", record.postAuthPrincipal());
        assertEquals(3, record.credentialVersion());
        assertEquals("key-3", record.keyId());
        assertEquals("corr-123456789012", record.correlationId());
        assertEquals("/external-api/v1/projects", record.routeTemplate());
        assertEquals("INTERSECTION_ALLOWED", record.dataScopeDecision());
        assertEquals("SUCCESS", record.resultCode());
        assertTrue(record.successFlag());
        org.mockito.Mockito.verify(service).recordRequired(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void auditPersistenceFailureChangesUncommittedResponseToFailClosed500() throws Exception {
        ExternalApiAuditService service = mock(ExternalApiAuditService.class);
        doThrow(new IllegalStateException("db unavailable")).when(service)
                .recordRequired(org.mockito.ArgumentMatchers.any());
        ExternalApiAuditBoundary boundary = boundary(service, mock(ExternalApiMetricsRecorder.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boundary.doFilter(request(), response, (req, res) -> { });

        assertEquals(500, response.getStatus());
        assertTrue(response.getContentAsString().contains("INTERNAL_ERROR"));
    }

    @Test
    void disabledPublicApiWritesNoAuditRowAndLeavesOnlyBoundedMetrics() throws Exception {
        ExternalApiAuditService service = mock(ExternalApiAuditService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationHubExternalApiProperties properties = properties(false, 60);
        ExternalApiAuditBoundary boundary = new ExternalApiAuditBoundary(providerOf(service),
                providerOf(new ExternalApiMetricsRecorder(providerOf(registry))), providerOf(properties),
                providerOf(budget(properties)), new ObjectMapper());
        ExternalApiDisabledFilter disabled = new ExternalApiDisabledFilter(providerOf(properties), new ObjectMapper());

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boundary.doFilter(request(), response, (req, res) -> disabled.doFilter(req, res, (a, b) -> { }));
            assertEquals(404, response.getStatus());
            assertTrue(response.getContentAsString().contains("RESOURCE_NOT_FOUND"));
        }

        verify(service, never()).recordRequired(org.mockito.ArgumentMatchers.any());
        assertEquals(3.0, suppressed(registry, "PUBLIC_API_DISABLED"));
        assertEquals(3.0, registry.get("integration_hub_external_api_requests_total")
                .tag("outcome", "DISABLED").counter().count());
    }

    @Test
    void missingPropertiesAreTreatedAsDisabledAndNeverPersist() throws Exception {
        ExternalApiAuditService service = mock(ExternalApiAuditService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExternalApiAuditBoundary boundary = new ExternalApiAuditBoundary(providerOf(service),
                providerOf(new ExternalApiMetricsRecorder(providerOf(registry))), providerOf(null),
                providerOf(null), new ObjectMapper());

        boundary.doFilter(request(), new MockHttpServletResponse(), (req, res) -> { });

        verify(service, never()).recordRequired(org.mockito.ArgumentMatchers.any());
        assertEquals(1.0, suppressed(registry, "PUBLIC_API_DISABLED"));
    }

    @Test
    void enabledUnauthenticatedRejectsArePersistedOnlyWithinTheBudgetAndKeepTheirStatus() throws Exception {
        ExternalApiAuditService service = mock(ExternalApiAuditService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntegrationHubExternalApiProperties properties = properties(true, 2);
        ExternalApiAuditBoundary boundary = new ExternalApiAuditBoundary(providerOf(service),
                providerOf(new ExternalApiMetricsRecorder(providerOf(registry))), providerOf(properties),
                providerOf(budget(properties)), new ObjectMapper());

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boundary.doFilter(request(), response, (req, res) -> {
                req.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "SIGNATURE_INVALID");
                ExternalApiErrorWriter.write((jakarta.servlet.http.HttpServletResponse) res, new ObjectMapper(),
                        "corr-unauthenticated", 401, "AUTHENTICATION_FAILED", false, 0);
            });
            assertEquals(401, response.getStatus(), "上限超過でもreject応答を500へ変えない");
        }

        verify(service, times(2)).recordRequired(org.mockito.ArgumentMatchers.any());
        assertEquals(1.0, suppressed(registry, "UNAUTHENTICATED_BUDGET"));
    }

    @Test
    void authenticatedRequestsAndServerErrorsBypassTheUnauthenticatedBudget() throws Exception {
        ExternalApiAuditService service = mock(ExternalApiAuditService.class);
        IntegrationHubExternalApiProperties properties = properties(true, 0);
        ExternalApiAuditBoundary boundary = new ExternalApiAuditBoundary(providerOf(service),
                providerOf(mock(ExternalApiMetricsRecorder.class)), providerOf(properties),
                providerOf(budget(properties)), new ObjectMapper());

        MockHttpServletRequest authenticated = request();
        authenticated.setAttribute(ExternalApiErrorWriter.PRINCIPAL_ATTRIBUTE, new ExternalApiPrincipal("client-a", 7L,
                "tenant-a", 9L, "{\"projectIds\":[\"p-1\"]}", 3, "key-3", "STANDARD"));
        boundary.doFilter(authenticated, new MockHttpServletResponse(), (req, res) -> { });

        MockHttpServletResponse serverError = new MockHttpServletResponse();
        boundary.doFilter(request(), serverError, (req, res) ->
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(500));

        verify(service, times(2)).recordRequired(org.mockito.ArgumentMatchers.any());
    }

    private ExternalApiAuditBoundary boundary(ExternalApiAuditService service,
                                               ExternalApiMetricsRecorder metrics) {
        IntegrationHubExternalApiProperties properties = properties(true, 60);
        return new ExternalApiAuditBoundary(providerOf(service), providerOf(metrics), providerOf(properties),
                providerOf(budget(properties)), new ObjectMapper());
    }

    private static IntegrationHubExternalApiProperties properties(boolean enabled, int unauthenticatedPerMinute) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(enabled);
        properties.getPublicApi().setUnauthenticatedAuditMaxPerMinute(unauthenticatedPerMinute);
        properties.getExternalTransport().setEnabled(false);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        return properties;
    }

    private static ExternalApiUnauthenticatedAuditBudget budget(IntegrationHubExternalApiProperties properties) {
        return new ExternalApiUnauthenticatedAuditBudget(providerOf(properties),
                providerOf(Clock.fixed(Instant.parse("2026-11-14T00:00:30Z"), ZoneOffset.UTC)));
    }

    private static double suppressed(SimpleMeterRegistry registry, String reason) {
        return registry.get("integration_hub_external_api_audit_suppressed_total")
                .tag("reason", reason).counter().count();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/external-api/v1/projects");
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
