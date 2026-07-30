package com.ses.config;

import com.ses.service.security.PersistentSessionService;
import com.ses.service.security.BreakGlassService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentSessionFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 未追跡sessionを拒否する際もsession未生成でnull例外にしない() throws Exception {
        PersistentSessionService service = mock(PersistentSessionService.class);
        when(service.validateAndTouch(any(), any())).thenReturn(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<PersistentSessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        @SuppressWarnings("unchecked")
        ObjectProvider<BreakGlassService> breakGlassProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.ses.service.AuditLogService> auditLogProvider = mock(ObjectProvider.class);
        PersistentSessionFilter filter = new PersistentSessionFilter(provider, breakGlassProvider, auditLogProvider);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(302, response.getStatus());
        assertEquals("/login?sessionExpired", response.getRedirectedUrl());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void DENY_SCOPE時に監査サービス不在なら503を返しchainを停止する() throws Exception {
        PersistentSessionService service = mock(PersistentSessionService.class);
        when(service.validateAndTouch(any(), any())).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<PersistentSessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        BreakGlassService breakGlassService = mock(BreakGlassService.class);
        when(breakGlassService.validateBoundSession(any(), any())).thenReturn(BreakGlassService.BreakGlassDecision.DENY_SCOPE);
        @SuppressWarnings("unchecked")
        ObjectProvider<BreakGlassService> breakGlassProvider = mock(ObjectProvider.class);
        when(breakGlassProvider.getIfAvailable()).thenReturn(breakGlassService);

        @SuppressWarnings("unchecked")
        ObjectProvider<com.ses.service.AuditLogService> auditLogProvider = mock(ObjectProvider.class);
        when(auditLogProvider.getIfAvailable()).thenReturn(null);

        PersistentSessionFilter filter = new PersistentSessionFilter(provider, breakGlassProvider, auditLogProvider);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("BG-01", null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/engineers/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void DENY_SCOPE時に監査記録例外なら503を返しchainを停止する() throws Exception {
        PersistentSessionService service = mock(PersistentSessionService.class);
        when(service.validateAndTouch(any(), any())).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<PersistentSessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        BreakGlassService breakGlassService = mock(BreakGlassService.class);
        when(breakGlassService.validateBoundSession(any(), any())).thenReturn(BreakGlassService.BreakGlassDecision.DENY_SCOPE);
        @SuppressWarnings("unchecked")
        ObjectProvider<BreakGlassService> breakGlassProvider = mock(ObjectProvider.class);
        when(breakGlassProvider.getIfAvailable()).thenReturn(breakGlassService);

        com.ses.service.AuditLogService auditLogService = mock(com.ses.service.AuditLogService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db error")).when(auditLogService).recordRequired(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        @SuppressWarnings("unchecked")
        ObjectProvider<com.ses.service.AuditLogService> auditLogProvider = mock(ObjectProvider.class);
        when(auditLogProvider.getIfAvailable()).thenReturn(auditLogService);

        PersistentSessionFilter filter = new PersistentSessionFilter(provider, breakGlassProvider, auditLogProvider);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("BG-01", null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/engineers/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }
}
