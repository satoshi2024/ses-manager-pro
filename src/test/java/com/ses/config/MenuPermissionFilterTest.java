package com.ses.config;

import com.ses.service.MenuCacheService;
import com.ses.service.security.AuthorizationService;
import com.ses.entity.Menu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

    @ParameterizedTest
    @CsvSource({
            "POST,/api/work-records/10/approve,work-record.approve",
            "PUT,/api/organizations/12,organization.update",
            "GET,/api/invoices,invoice.view",
            "PUT,/api/profile/password,profile.update"
    })
    void restrictiveGroupで未許可の直接consumerを拒否する(String method, String uri, String action)
            throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<MenuCacheService> menuProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.ses.service.AuditLogService> auditProvider = mock(ObjectProvider.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationProvider.getIfAvailable()).thenReturn(authorizationService);
        when(authorizationService.isAllowed(any(), org.mockito.ArgumentMatchers.eq(action))).thenReturn(false);
        MenuPermissionFilter filter = new MenuPermissionFilter(menuProvider, authorizationProvider, auditProvider);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(authorizationService).isAllowed(any(), org.mockito.ArgumentMatchers.eq(action));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void restrictiveGroupはApiと同じactionがないpage直達も拒否する() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<MenuCacheService> menuProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<com.ses.service.AuditLogService> auditProvider = mock(ObjectProvider.class);
        com.ses.service.AuditLogService auditLogService = mock(com.ses.service.AuditLogService.class);
        when(auditProvider.getIfAvailable()).thenReturn(auditLogService);
        MenuCacheService menuCache = mock(MenuCacheService.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        Menu menu = new Menu();
        menu.setMenuKey("management-accounting");
        menu.setPathPrefix("/management-accounting");
        menu.setApiPrefix("/api/management-accounting");
        when(menuProvider.getIfAvailable()).thenReturn(menuCache);
        when(authorizationProvider.getIfAvailable()).thenReturn(authorizationService);
        when(menuCache.getAllMenus()).thenReturn(java.util.List.of(menu));
        when(menuCache.getMenuKeysByRole("営業")).thenReturn(java.util.List.of("management-accounting"));
        when(authorizationService.isAllowed(any(), org.mockito.ArgumentMatchers.eq("management-accounting.view")))
                .thenReturn(false);
        MenuPermissionFilter filter = new MenuPermissionFilter(menuProvider, authorizationProvider, auditProvider);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "7", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_営業"))));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/management-accounting/budget");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(authorizationService).isAllowed(any(), org.mockito.ArgumentMatchers.eq("management-accounting.view"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void portal実行層が未導入の間は管理者も直接到達できない() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<MenuCacheService> menuProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<com.ses.service.AuditLogService> auditProvider = mock(ObjectProvider.class);
        com.ses.service.AuditLogService auditLogService = mock(com.ses.service.AuditLogService.class);
        when(auditProvider.getIfAvailable()).thenReturn(auditLogService);
        MenuPermissionFilter filter = new MenuPermissionFilter(menuProvider, authorizationProvider, auditProvider);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portal/future");
        request.getSession(true).setAttribute(com.ses.service.security.BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(auditLogService).recordRequired(any(), org.mockito.ArgumentMatchers.eq("GET"),
                org.mockito.ArgumentMatchers.eq("/api/portal/future"), org.mockito.ArgumentMatchers.eq(403),
                org.mockito.ArgumentMatchers.eq("BREAK_GLASS_PERMISSION_DENIED"),
                org.mockito.ArgumentMatchers.eq(false));
        verify(chain, never()).doFilter(any(), any());
    }
}
