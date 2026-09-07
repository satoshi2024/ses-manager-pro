package com.ses.service.ai.copilot.scope;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotScopeResolverTest {

    @Mock
    private DataScopeService dataScopeService;

    @Mock
    private OrganizationScopeService organizationScopeService;

    @InjectMocks
    private CopilotScopeResolver resolver;

    private static final CopilotExecutionContext CONTEXT = contextOn(LocalDate.of(2026, 9, 7));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void HRは拒否される() {
        loginAs("hr", "HR");
        SemanticCatalogEntry entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();
        assertThrows(BusinessException.class, () -> resolver.resolve(entry, CONTEXT));
    }

    @Test
    void 管理者はCOMPANY_WIDE() {
        loginAs("admin", "管理者");
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(dataScopeService.isScoped()).thenReturn(false);

        CopilotScopeContext scope = resolver.resolve(
                SemanticCatalogRegistry.find("dashboard.summary").orElseThrow(), CONTEXT);
        assertEquals("COMPANY_WIDE", scope.scopeType());
    }

    @Test
    void 営業はSALES_DATA_SCOPED() {
        loginAs("sales", "営業");
        when(dataScopeService.isSalesDataScoped()).thenReturn(true);
        when(dataScopeService.allowedContractIds()).thenReturn(Set.of(1L, 2L));
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of(3L));
        when(dataScopeService.allowedCustomerIds()).thenReturn(Set.of());

        CopilotScopeContext scope = resolver.resolve(
                SemanticCatalogRegistry.find("dashboard.summary").orElseThrow(), CONTEXT);
        assertEquals("SALES_DATA_SCOPED", scope.scopeType());
    }

    @Test
    void 営業の空母集団はfailClosed() {
        loginAs("sales", "営業");
        when(dataScopeService.isSalesDataScoped()).thenReturn(true);
        when(dataScopeService.allowedContractIds()).thenReturn(Set.of());
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of());
        when(dataScopeService.allowedCustomerIds()).thenReturn(Set.of());

        SemanticCatalogEntry entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();
        assertThrows(BusinessException.class, () -> resolver.resolve(entry, CONTEXT));
    }

    @Test
    void 異なる許可集合は異なるscopeHashになる() {
        loginAs("sales", "営業");
        when(dataScopeService.isSalesDataScoped()).thenReturn(true);

        when(dataScopeService.allowedContractIds()).thenReturn(Set.of(1L, 2L));
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of());
        when(dataScopeService.allowedCustomerIds()).thenReturn(Set.of());
        CopilotScopeContext scope12 = resolver.resolve(
                SemanticCatalogRegistry.find("dashboard.summary").orElseThrow(), CONTEXT);

        when(dataScopeService.allowedContractIds()).thenReturn(Set.of(3L, 4L));
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of());
        when(dataScopeService.allowedCustomerIds()).thenReturn(Set.of());
        CopilotScopeContext scope34 = resolver.resolve(
                SemanticCatalogRegistry.find("dashboard.summary").orElseThrow(), CONTEXT);

        assertNotEquals(scope12.scopeHash(), scope34.scopeHash());
    }

    @Test
    void asOf変更でscopeHashが変わる() {
        loginAs("admin", "管理者");
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(dataScopeService.isScoped()).thenReturn(false);

        SemanticCatalogEntry entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();
        String hashDay1 = resolver.resolve(entry, contextOn(LocalDate.of(2026, 9, 7))).scopeHash();
        String hashDay2 = resolver.resolve(entry, contextOn(LocalDate.of(2026, 9, 8))).scopeHash();
        assertNotEquals(hashDay1, hashDay2);
    }

    private void loginAs(String username, String role) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername(username);
        user.setRole(role);
        LoginUser loginUser = new LoginUser(user, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private static CopilotExecutionContext contextOn(LocalDate asOf) {
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Instant instant = asOf.atStartOfDay(zone).plusHours(12).toInstant();
        Clock clock = Clock.fixed(instant, zone);
        return new CopilotExecutionContext(
                clock,
                instant,
                zone,
                asOf,
                YearMonth.from(asOf),
                "default",
                "");
    }
}
