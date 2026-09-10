package com.ses.service.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** NF-02のService Desk顧客scopeのrole別境界を固定する。 */
class CustomerScopeResolverTest {

    private final DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);
    private final OrganizationScopeService organizationScopeService = Mockito.mock(OrganizationScopeService.class);
    private final CustomerScopeResolver resolver = new CustomerScopeResolver(dataScopeService, organizationScopeService);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void managerは組織scopeとDataScopeの積集合() {
        login("マネージャー");
        Mockito.when(dataScopeService.isScoped()).thenReturn(true);
        Mockito.when(dataScopeService.allowedCustomerIds()).thenReturn(Set.of(1L, 2L));
        Mockito.when(organizationScopeService.hasFullAccess()).thenReturn(false);
        Mockito.when(organizationScopeService.allowedCustomerIds(Mockito.any(LocalDate.class)))
                .thenReturn(Set.of(2L, 3L));

        assertEquals(Set.of(2L), resolver.resolve(LocalDate.of(2026, 9, 1)));
    }

    @Test
    void salesはDataScopeだけを使いHR要員はdeny() {
        Mockito.when(dataScopeService.isScoped()).thenReturn(true);
        Mockito.when(dataScopeService.allowedCustomerIds()).thenReturn(Set.of(7L));

        login("営業");
        assertEquals(Set.of(7L), resolver.resolve(null));
        login("HR");
        assertEquals(Set.of(), resolver.resolve(null));
        login("要員");
        assertEquals(Set.of(), resolver.resolve(null));
    }

    @Test
    void adminは全件を表すnull() {
        login("管理者");
        assertNull(resolver.resolve(null));
    }

    @Test
    void 未定義ロールはDataScope無効でもdeny() {
        login("UNKNOWN");
        Mockito.when(dataScopeService.isScoped()).thenReturn(false);

        assertEquals(Set.of(), resolver.resolve(null));
    }

    private void login(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("100", "n", "ROLE_" + role));
    }
}
