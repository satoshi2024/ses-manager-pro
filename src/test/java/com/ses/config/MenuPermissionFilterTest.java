package com.ses.config;

import com.ses.service.MenuCacheService;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MenuPermissionFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void menuCache障害時は未知ApiもfailClosedにする() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<MenuCacheService> menuProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.ses.service.AuditLogService> auditProvider = mock(ObjectProvider.class);
        com.ses.service.AuditLogService auditLogService = mock(com.ses.service.AuditLogService.class);
        when(auditProvider.getIfAvailable()).thenReturn(auditLogService);
        MenuCacheService menuCache = mock(MenuCacheService.class);
        when(menuProvider.getIfAvailable()).thenReturn(menuCache);
        when(menuCache.getAllMenus()).thenThrow(new IllegalStateException("db unavailable"));
        MenuPermissionFilter filter = new MenuPermissionFilter(menuProvider, authorizationProvider, auditProvider);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/unknown-sensitive");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
        verify(auditLogService).record(any(), org.mockito.ArgumentMatchers.eq("GET"),
                org.mockito.ArgumentMatchers.eq("/api/unknown-sensitive"),
                org.mockito.ArgumentMatchers.eq(403),
                org.mockito.ArgumentMatchers.eq("PERMISSION_DENIED"),
                org.mockito.ArgumentMatchers.eq(false));
    }
}
