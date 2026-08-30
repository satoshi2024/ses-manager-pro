package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.service.integrationhub.ExternalApiAuditRecord;
import com.ses.service.integrationhub.ExternalApiAuditService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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

    private ExternalApiAuditBoundary boundary(ExternalApiAuditService service,
                                               ExternalApiMetricsRecorder metrics) {
        return new ExternalApiAuditBoundary(providerOf(service), providerOf(metrics), new ObjectMapper());
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
