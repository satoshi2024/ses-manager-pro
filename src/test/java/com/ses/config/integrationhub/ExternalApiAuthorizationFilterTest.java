package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.integrationhub.ApiClientScope;
import com.ses.service.integrationhub.ApiClientScopeService;
import com.ses.service.integrationhub.ApiUsageBucketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalApiAuthorizationFilterTest {
    private final ApiClientScopeService scopeService = mock(ApiClientScopeService.class);
    private final ApiUsageBucketService usageService = mock(ApiUsageBucketService.class);
    private final ExternalApiAuthorizationFilter filter = new ExternalApiAuthorizationFilter(
            providerOf(scopeService), providerOf(usageService), new ObjectMapper());

    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @AfterEach
    void clearContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void scopeDataScopeAndQuotaAreAppliedUsingFiniteRouteTemplate() throws Exception {
        ExternalApiPrincipal principal = principal();
        authenticate(principal);
        when(scopeService.getActive(7L, ExternalApiRouteCatalog.PROJECT_SCOPE,
                ExternalApiRouteCatalog.PROJECT_PERMISSION)).thenReturn(ApiClientScope.builder()
                .dataScopeJson("{\"projectIds\":[\"p-1\"]}").build());
        when(usageService.consume("client-a", ExternalApiRouteCatalog.PROJECT_SCOPE, "tenant-a",
                "/external-api/v1/projects/{publicProjectId}")).thenReturn(ApiUsageBucketService.RateDecision.allow());
        MockHttpServletRequest request = request("GET", "/external-api/v1/projects/p-1");
        request.setAttribute(ExternalApiCanonicalRequest.class.getName(), parsed("/external-api/v1/projects/p-1"));
        request.setAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE, "correlation-123456");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertTrue(called.get());
        ExternalApiEffectiveScope effective = assertInstanceOf(ExternalApiEffectiveScope.class,
                request.getAttribute(ExternalApiEffectiveScope.class.getName()));
        assertEquals("tenant-a", effective.tenantId());
        assertEquals(9L, effective.legalEntityId());
        assertEquals(java.util.Set.of("p-1"), effective.allowedValues().get("projectIds"));
        verify(usageService).consume("client-a", ExternalApiRouteCatalog.PROJECT_SCOPE, "tenant-a",
                "/external-api/v1/projects/{publicProjectId}");
    }

    @Test
    void missingScopeIsForbiddenAndDoesNotConsumeQuota() throws Exception {
        authenticate(principal());
        when(scopeService.getActive(any(), any(), any())).thenReturn(null);
        MockHttpServletRequest request = request("GET", "/external-api/v1/projects");
        request.setAttribute(ExternalApiCanonicalRequest.class.getName(), parsed("/external-api/v1/projects"));
        request.setAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE, "correlation-123456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("must not reach controller"); });

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("FORBIDDEN_SCOPE"));
        verify(usageService, never()).consume(any(), any(), any(), any());
    }

    @Test
    void quotaDenialReturnsRetryAfterAndCommandIsDefaultDenied() throws Exception {
        authenticate(principal());
        when(scopeService.getActive(any(), any(), any())).thenReturn(ApiClientScope.builder()
                .dataScopeJson("{\"projectIds\":[\"p-1\"]}").build());
        when(usageService.consume(any(), any(), any(), any())).thenReturn(
                new ApiUsageBucketService.RateDecision(false, 7, java.util.Set.of("MINUTE")));
        MockHttpServletRequest request = request("GET", "/external-api/v1/projects");
        request.setAttribute(ExternalApiCanonicalRequest.class.getName(), parsed("/external-api/v1/projects"));
        request.setAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE, "correlation-123456");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        assertEquals(429, response.getStatus());
        assertEquals("7", response.getHeader("Retry-After"));

        MockHttpServletRequest command = request("POST", "/external-api/v1/projects");
        command.setAttribute(ExternalApiCanonicalRequest.class.getName(), parsed("/external-api/v1/projects"));
        command.setAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE, "correlation-123456");
        MockHttpServletResponse commandResponse = new MockHttpServletResponse();
        filter.doFilter(command, commandResponse, (req, res) -> { throw new AssertionError("command allowed"); });
        assertEquals(404, commandResponse.getStatus());
        assertFalse(commandResponse.getContentAsString().contains("FORBIDDEN_SCOPE"));
    }

    @Test
    void nonIntersectingClientAndRouteScopeIsDeniedBeforeQuota() throws Exception {
        authenticate(principal());
        when(scopeService.getActive(any(), any(), any())).thenReturn(ApiClientScope.builder()
                .dataScopeJson("{\"projectIds\":[\"p-2\"]}").build());
        MockHttpServletRequest request = request("GET", "/external-api/v1/projects");
        request.setAttribute(ExternalApiCanonicalRequest.class.getName(), parsed("/external-api/v1/projects"));
        request.setAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE, "correlation-123456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("must not reach controller"); });

        assertEquals(403, response.getStatus());
        verify(usageService, never()).consume(any(), any(), any(), any());
    }

    @Test
    void malformedOrWildcardScopeIsDeniedClosed() throws Exception {
        authenticate(principal());
        when(scopeService.getActive(any(), any(), any())).thenReturn(ApiClientScope.builder()
                .dataScopeJson("{\"projectIds\":[\"*\"]}").build());
        MockHttpServletRequest request = request("GET", "/external-api/v1/projects");
        request.setAttribute(ExternalApiCanonicalRequest.class.getName(), parsed("/external-api/v1/projects"));
        request.setAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE, "correlation-123456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("must not reach controller"); });

        assertEquals(403, response.getStatus());
        verify(usageService, never()).consume(any(), any(), any(), any());
    }

    @Test
    void tenantOrLegalEntityMismatchIsDeniedBeforeResourcePermission() throws Exception {
        authenticate(principal());
        when(scopeService.getActive(any(), any(), any())).thenReturn(ApiClientScope.builder()
                .dataScopeJson("{\"projectIds\":[\"p-1\"],\"tenantIds\":[\"tenant-b\"],\"legalEntityIds\":[\"10\"]}")
                .build());
        MockHttpServletRequest request = request("GET", "/external-api/v1/projects/p-1");
        request.setAttribute(ExternalApiCanonicalRequest.class.getName(), parsed("/external-api/v1/projects/p-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("must not reach controller"); });

        assertEquals(403, response.getStatus());
        verify(usageService, never()).consume(any(), any(), any(), any());
    }

    @Test
    void tenantAndLegalEntityOmissionStillUsesPrincipalSingletons() throws Exception {
        authenticate(principal());
        when(scopeService.getActive(any(), any(), any())).thenReturn(ApiClientScope.builder()
                .dataScopeJson("{\"projectIds\":[\"p-1\"]}").build());
        MockHttpServletRequest request = request("GET", "/external-api/v1/projects/p-1");
        request.setAttribute(ExternalApiCanonicalRequest.class.getName(), parsed("/external-api/v1/projects/p-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(usageService.consume(any(), any(), any(), any())).thenReturn(ApiUsageBucketService.RateDecision.allow());

        filter.doFilter(request, response, (req, res) -> { });

        ExternalApiEffectiveScope effective = assertInstanceOf(ExternalApiEffectiveScope.class,
                request.getAttribute(ExternalApiEffectiveScope.class.getName()));
        assertEquals(java.util.Set.of("tenant-a"), effective.allowedValues().get("tenantIds"));
        assertEquals(java.util.Set.of("9"), effective.allowedValues().get("legalEntityIds"));
    }

    private void authenticate(ExternalApiPrincipal principal) {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new ExternalApiAuthenticationToken(principal));
    }

    private ExternalApiPrincipal principal() {
        return new ExternalApiPrincipal("client-a", 7L, "tenant-a", 9L,
                "{\"projectIds\":[\"p-1\"]}", 1, "key-1", "STANDARD");
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        return request;
    }

    private ExternalApiCanonicalRequest.Parsed parsed(String path) {
        return new ExternalApiCanonicalRequest.Parsed(path, path, new byte[0],
                com.ses.service.integrationhub.IntegrationHubDigest.sha256Hex(new byte[0]));
    }
}
