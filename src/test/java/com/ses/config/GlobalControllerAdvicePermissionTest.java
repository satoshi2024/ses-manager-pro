package com.ses.config;

import com.ses.entity.Menu;
import com.ses.service.MenuCacheService;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalControllerAdvicePermissionTest {

    @Test
    void sidebarはroleMenuとgroupActionの積集合だけを表示する() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MenuCacheService> menuProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        MenuCacheService menuCache = mock(MenuCacheService.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(menuProvider.getIfAvailable()).thenReturn(menuCache);
        when(authorizationProvider.getIfAvailable()).thenReturn(authorizationService);
        when(menuCache.getMenuKeysByRole("営業")).thenReturn(List.of("invoice", "organization"));
        when(menuCache.getAllMenus()).thenReturn(List.of(
                menu("invoice", "/api/invoices"), menu("organization", "/api/organizations")));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "sales", null, List.of(new SimpleGrantedAuthority("ROLE_営業")));
        when(authorizationService.isAllowed(authentication, "invoice.view")).thenReturn(true);
        when(authorizationService.isAllowed(authentication, "organization.view")).thenReturn(false);

        GlobalControllerAdvice advice = new GlobalControllerAdvice(menuProvider, authorizationProvider);

        assertEquals(List.of("invoice"), advice.allowedMenus(authentication));
    }

    private Menu menu(String key, String apiPrefix) {
        Menu menu = new Menu();
        menu.setMenuKey(key);
        menu.setApiPrefix(apiPrefix);
        return menu;
    }
}
