package com.ses.config;

import com.ses.entity.Menu;
import com.ses.service.MenuCacheService;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

/**
 * R23-P1-01 §5: MenuPermissionFilterのcompliance-gate境界テスト。
 * m_menu（V102_2）＋ActionPermissionResolver（java）登録を前提に、
 * menu許可のないロール（営業・要員）は403・許可ロール（管理者/HR/マネージャー）は通過する。
 */
class ComplianceGateMenuPermissionTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Menu complianceGateMenu() {
        Menu menu = new Menu();
        menu.setMenuKey("compliance-gate");
        menu.setPathPrefix("/compliance-gate");
        menu.setApiPrefix("/api/compliance-gate");
        return menu;
    }

    private MenuPermissionFilter filter(MenuCacheService menuCache, AuthorizationService authorizationService,
                                        com.ses.service.AuditLogService auditLogService) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MenuCacheService> menuProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.ses.service.AuditLogService> auditProvider = mock(ObjectProvider.class);
        when(menuProvider.getIfAvailable()).thenReturn(menuCache);
        when(authorizationProvider.getIfAvailable()).thenReturn(authorizationService);
        when(auditProvider.getIfAvailable()).thenReturn(auditLogService);
        return new MenuPermissionFilter(menuProvider, authorizationProvider, auditProvider);
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void complianceGateはmenu許可のある管理者HRマネージャーが通れる() throws Exception {
        for (String role : java.util.List.of("管理者", "HR", "マネージャー")) {
            tearDown();
            MenuCacheService menuCache = mock(MenuCacheService.class);
            AuthorizationService authorizationService = mock(AuthorizationService.class);
            com.ses.service.AuditLogService auditLogService = mock(com.ses.service.AuditLogService.class);
            when(menuCache.getAllMenus()).thenReturn(java.util.List.of(complianceGateMenu()));
            when(menuCache.getMenuKeysByRole(role)).thenReturn(java.util.List.of("compliance-gate"));
            when(authorizationService.isAllowed(any(), org.mockito.ArgumentMatchers.eq("compliance-gate.view")))
                    .thenReturn(true);
            MenuPermissionFilter filter = filter(menuCache, authorizationService, auditLogService);
            authenticate("9", role);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/compliance-gate/mappings");
            MockHttpServletResponse response = new MockHttpServletResponse();
            jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

            filter.doFilter(request, response, chain);

            assertEquals(200, response.getStatus(), "role=" + role);
            verify(chain).doFilter(any(), any());
        }
    }

    @Test
    void complianceGateはmenu未付与の営業要員から到達できない() throws Exception {
        for (String role : java.util.List.of("営業", "要員")) {
            tearDown();
            MenuCacheService menuCache = mock(MenuCacheService.class);
            AuthorizationService authorizationService = mock(AuthorizationService.class);
            com.ses.service.AuditLogService auditLogService = mock(com.ses.service.AuditLogService.class);
            when(menuCache.getAllMenus()).thenReturn(java.util.List.of(complianceGateMenu()));
            when(menuCache.getMenuKeysByRole(role)).thenReturn(java.util.List.of("engineer"));
            when(authorizationService.isAllowed(any(), org.mockito.ArgumentMatchers.eq("compliance-gate.view")))
                    .thenReturn(false);
            MenuPermissionFilter filter = filter(menuCache, authorizationService, auditLogService);
            authenticate("7", role);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/compliance-gate/mappings");
            MockHttpServletResponse response = new MockHttpServletResponse();
            jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

            filter.doFilter(request, response, chain);

            assertEquals(403, response.getStatus(), "role=" + role);
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    void complianceGateのpage直達は管理者でも403にならない() throws Exception {
        MenuCacheService menuCache = mock(MenuCacheService.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        com.ses.service.AuditLogService auditLogService = mock(com.ses.service.AuditLogService.class);
        when(menuCache.getAllMenus()).thenReturn(java.util.List.of(complianceGateMenu()));
        when(menuCache.getMenuKeysByRole("管理者")).thenReturn(java.util.List.of("compliance-gate"));
        when(authorizationService.isAllowed(any(), any())).thenReturn(true);
        MenuPermissionFilter filter = filter(menuCache, authorizationService, auditLogService);
        authenticate("1", "管理者");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/compliance-gate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(any(), any());
    }
}
