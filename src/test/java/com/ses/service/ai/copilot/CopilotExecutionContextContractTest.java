package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.SysUser;
import com.ses.mapper.AttendanceScopeMapper;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.security.LegalEntityReadinessService;
import com.ses.service.accounting.AccountingTimezoneResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF-08 execution contextの固定時刻、Tokyo境界、法人入りscope hashを確認する。 */
class CopilotExecutionContextContractTest {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private final SemanticCatalogEntry entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 固定ClockのUTC月末月初をTokyoのasOfへ一度だけ束縛する() {
        CopilotExecutionContext endOfMonth = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-03-31T14:59:59Z"), TOKYO);
        CopilotExecutionContext startOfMonth = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-03-31T15:00:00Z"), TOKYO);

        assertEquals("2026-03-31", endOfMonth.asOfDate().toString());
        assertEquals("2026-03", endOfMonth.asOfMonth().toString());
        assertEquals("2026-04-01", startOfMonth.asOfDate().toString());
        assertEquals("2026-04", startOfMonth.asOfMonth().toString());
        var parameters = com.ses.service.ai.copilot.parameter.CopilotQueryParameters.ofQuery("dashboard.summary");
        var scope = new CopilotScopeContext("COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, "hash", false,
                "tenant-a", 10L, "ALL");
        endOfMonth.bind("dashboard.summary", parameters, scope);
        assertThrows(IllegalStateException.class, () -> endOfMonth.bind("dashboard.summary", parameters, scope));
    }

    @Test
    void 同じscope集合とasOfでも法人が違えばscopeHashが違う() {
        loginAs("admin", "管理者", "tenant-a");
        DataScopeService dataScope = mock(DataScopeService.class);
        OrganizationScopeService organizationScope = mock(OrganizationScopeService.class);
        when(organizationScope.hasFullAccess()).thenReturn(true);
        when(dataScope.isScoped()).thenReturn(false);
        CopilotScopeResolver resolver = new CopilotScopeResolver(
                dataScope, organizationScope);

        CopilotExecutionContext firstContext = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-08-31T00:00:00Z"), TOKYO);
        firstContext.bindSnapshot(new EffectiveScopeSnapshotFactory(dataScope, organizationScope)
                .create("tenant-a", 10L, firstContext.asOfDate()));
        CopilotExecutionContext secondContext = new CopilotExecutionContext(
                "tenant-a", 20L, Instant.parse("2026-08-31T00:00:00Z"), TOKYO);
        secondContext.bindSnapshot(new EffectiveScopeSnapshotFactory(dataScope, organizationScope)
                .create("tenant-a", 20L, secondContext.asOfDate()));

        CopilotScopeContext first = resolver.resolve(entry, firstContext);
        CopilotScopeContext second = resolver.resolve(entry, secondContext);

        assertNotEquals(first.scopeHash(), second.scopeHash());
        assertEquals("ALL", first.canonicalMembers());
        assertEquals(10L, first.legalEntityId());
    }

    @Test
    void 法人コンテキストが無いfactoryはfailClosedする() {
        AttendanceScopeMapper attendance = mock(AttendanceScopeMapper.class);
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId("tenant-a");
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        LegalEntityReadinessService readiness = mock(LegalEntityReadinessService.class);
        when(timezoneResolver.resolve("tenant-a")).thenReturn(TOKYO);
        when(attendance.selectAllLegalEntityIds()).thenReturn(List.of());
        loginAs("admin", "管理者", "tenant-a");

        CopilotExecutionContextFactory factory = new CopilotExecutionContextFactory(
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), TOKYO), attendance, oidc, timezoneResolver,
                readiness);

        assertThrows(BusinessException.class, factory::create);
    }

    @Test
    void 非Tokyoテナントもtimezoneを一度だけ解決しasOfへ束縛する() {
        AttendanceScopeMapper attendance = mock(AttendanceScopeMapper.class);
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId("tenant-new-york");
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        LegalEntityReadinessService readiness = mock(LegalEntityReadinessService.class);
        ZoneId newYork = ZoneId.of("America/New_York");
        when(timezoneResolver.resolve("tenant-new-york")).thenReturn(newYork);
        when(attendance.selectAllLegalEntityIds()).thenReturn(List.of(10L));
        loginAs("admin", "管理者", "tenant-new-york");

        CopilotExecutionContextFactory factory = new CopilotExecutionContextFactory(
                Clock.fixed(Instant.parse("2026-04-01T03:59:59Z"), ZoneId.of("UTC")),
                attendance, oidc, timezoneResolver, readiness);

        CopilotExecutionContext context = factory.create();

        assertEquals(newYork, context.zoneId());
        assertEquals("2026-03-31", context.asOfDate().toString());
        assertEquals("2026-03", context.asOfMonth().toString());
        verify(timezoneResolver, times(1)).resolve("tenant-new-york");
    }

    @Test
    void UTCテナントも日付境界をcontextへ束縛する() {
        AttendanceScopeMapper attendance = mock(AttendanceScopeMapper.class);
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId("tenant-utc");
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        LegalEntityReadinessService readiness = mock(LegalEntityReadinessService.class);
        when(timezoneResolver.resolve("tenant-utc")).thenReturn(ZoneId.of("UTC"));
        when(attendance.selectAllLegalEntityIds()).thenReturn(List.of(10L));
        loginAs("admin", "管理者", "tenant-utc");

        CopilotExecutionContext context = new CopilotExecutionContextFactory(
                Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneId.of("UTC")),
                attendance, oidc, timezoneResolver, readiness).create();

        assertEquals(ZoneId.of("UTC"), context.zoneId());
        assertEquals("2026-04-01", context.asOfDate().toString());
        assertEquals("2026-04", context.asOfMonth().toString());
        verify(timezoneResolver, times(1)).resolve("tenant-utc");
    }

    @Test
    void snapshotが無いcontextはscopeを再計算せず拒否する() {
        loginAs("admin", "管理者", "tenant-a");
        DataScopeService dataScope = mock(DataScopeService.class);
        OrganizationScopeService organizationScope = mock(OrganizationScopeService.class);
        when(organizationScope.hasFullAccess()).thenReturn(true);
        when(dataScope.isScoped()).thenReturn(true);
        when(dataScope.isSalesDataScoped()).thenReturn(true);
        java.time.LocalDate asOf = java.time.LocalDate.of(2026, 8, 31);
        when(dataScope.allowedContractIds(asOf)).thenReturn(Set.of(1L));
        when(dataScope.allowedEngineerIds(asOf)).thenReturn(Set.of(2L));
        when(dataScope.allowedCustomerIds(asOf)).thenReturn(Set.of(3L));
        CopilotScopeResolver resolver = new CopilotScopeResolver(dataScope, organizationScope);
        CopilotExecutionContext context = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-08-31T00:00:00Z"), TOKYO);

        EffectiveScopeSnapshot snapshot = new EffectiveScopeSnapshotFactory(dataScope, organizationScope)
                .create("tenant-a", 10L, asOf);
        context.bindSnapshot(snapshot);
        CopilotScopeContext first = resolver.resolve(entry, context);
        when(dataScope.allowedEngineerIds(asOf)).thenReturn(Set.of(4L));

        assertEquals(snapshot.scope(), first);
        BusinessException denied = assertThrows(BusinessException.class,
                () -> resolver.resolve(entry, new CopilotExecutionContext(
                        "tenant-a", 10L, Instant.parse("2026-08-31T00:00:00Z"), TOKYO)));
        assertEquals("SCOPE_CONTEXT_REQUIRED", denied.getMessage());
    }

    private void loginAs(String username, String role, String tenantId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername(username);
        user.setRole(role);
        LoginUser loginUser = new LoginUser(user, List.of(), tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }
}
