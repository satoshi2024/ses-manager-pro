package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.entity.SysUser;
import com.ses.mapper.AttendanceScopeMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 認証requestのtenantを正本とし、未束縛tenantやdefault/1L推測を拒否する。 */
class LegalEntityContextServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T15:00:00Z"), ZoneId.of("UTC"));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 認証tenantを明示的にresolveし法人を一意に解決する() {
        AttendanceScopeMapper mapper = mock(AttendanceScopeMapper.class);
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId("tenant-a");
        when(timezoneResolver.resolve("tenant-a")).thenReturn(ZoneId.of("America/New_York"));
        when(mapper.selectAllLegalEntityIds()).thenReturn(List.of(42L));
        authenticate("tenant-a", "管理者", 1L);

        LegalEntityContextService service = new LegalEntityContextService(clock, mapper, timezoneResolver, properties);

        assertEquals(42L, service.requireCurrentLegalEntityId());
        verify(timezoneResolver).resolve("tenant-a");
    }

    @Test
    void tenant未束縛のprincipalはAccountingTenantContextHolderやdefaultへfallbackしない() {
        authenticate(null, "管理者", 1L);
        AccountingTenantContextHolder.setTenantId("holder-tenant");
        LegalEntityContextService service = new LegalEntityContextService(clock,
                mock(AttendanceScopeMapper.class), mock(AccountingTimezoneResolver.class), new OidcSecurityProperties());

        BusinessException ex = assertThrows(BusinessException.class, service::requireTenantId);
        assertEquals(403, ex.getCode());
        assertEquals("TENANT_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void SecurityTenant空でHolderに値があってもfailClosed() {
        authenticate(null, "管理者", 1L);
        AccountingTenantContextHolder.setTenantId("default");
        LegalEntityContextService service = new LegalEntityContextService(clock,
                mock(AttendanceScopeMapper.class), mock(AccountingTimezoneResolver.class), new OidcSecurityProperties());

        assertThrows(BusinessException.class, service::requireTenantId);
    }

    @Test
    void 認証tenantとdeploymentBindingが違えば拒否する() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId("tenant-a");
        authenticate("tenant-b", "管理者", 1L);
        LegalEntityContextService service = new LegalEntityContextService(clock,
                mock(AttendanceScopeMapper.class), mock(AccountingTimezoneResolver.class), properties);

        assertThrows(BusinessException.class, service::requireTenantId);
    }

    @Test
    void mapperNullはfailClosedで1Lを返さない() {
        authenticate("tenant-a", "管理者", 1L);
        LegalEntityContextService service = new LegalEntityContextService(clock, null,
                mock(AccountingTimezoneResolver.class), new OidcSecurityProperties());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireCurrentLegalEntityId(clock.instant(), ZoneId.of("Asia/Tokyo")));
        assertEquals(503, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void roleNullはfailClosedで1Lを返さない() {
        authenticate("tenant-a", null, 1L);
        AttendanceScopeMapper mapper = mock(AttendanceScopeMapper.class);
        when(mapper.selectAllLegalEntityIds()).thenReturn(List.of(1L));
        LegalEntityContextService service = new LegalEntityContextService(clock, mapper,
                mock(AccountingTimezoneResolver.class), new OidcSecurityProperties());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireCurrentLegalEntityId(clock.instant(), ZoneId.of("Asia/Tokyo")));
        assertEquals(403, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void 空法人リストはfailClosedで1Lを返さない() {
        authenticate("tenant-a", "管理者", 1L);
        AttendanceScopeMapper mapper = mock(AttendanceScopeMapper.class);
        when(mapper.selectAllLegalEntityIds()).thenReturn(List.of());
        LegalEntityContextService service = new LegalEntityContextService(clock, mapper,
                mock(AccountingTimezoneResolver.class), new OidcSecurityProperties());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireCurrentLegalEntityId(clock.instant(), ZoneId.of("Asia/Tokyo")));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("LEGAL_ENTITY"));
    }

    @Test
    void defaultTenantかつ空組織はfailClosedで1Lを返さない() {
        authenticate("default", "管理者", 1L);
        AttendanceScopeMapper mapper = mock(AttendanceScopeMapper.class);
        when(mapper.selectAllLegalEntityIds()).thenReturn(List.of());
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId("default");
        LegalEntityContextService service = new LegalEntityContextService(clock, mapper,
                mock(AccountingTimezoneResolver.class), properties);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireCurrentLegalEntityId(clock.instant(), ZoneId.of("Asia/Tokyo")));
        assertEquals(403, ex.getCode());
        assertEquals("LEGAL_ENTITY_CONTEXT_REQUIRED", ex.getMessage());
    }

    @Test
    void ExternalApiPrincipalのtenantは受け入れる() {
        ExternalApiPrincipal principal = new ExternalApiPrincipal(
                "client-a", 9L, "tenant-ext", 7L, "{}", 1, "key-1", "STANDARD");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        LegalEntityContextService service = new LegalEntityContextService(clock,
                mock(AttendanceScopeMapper.class), mock(AccountingTimezoneResolver.class), new OidcSecurityProperties());

        assertEquals("tenant-ext", service.requireTenantId());
    }

    @Test
    void 非securityBoundPrincipalはHolder補完せず拒否する() {
        User plain = new User("svc", "x", List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(plain, null, plain.getAuthorities()));
        AccountingTenantContextHolder.setTenantId("holder-only");
        LegalEntityContextService service = new LegalEntityContextService(clock,
                mock(AttendanceScopeMapper.class), mock(AccountingTimezoneResolver.class), new OidcSecurityProperties());

        assertThrows(BusinessException.class, service::requireTenantId);
    }

    @Test
    void requireTenantContextは明示もsecurityも無いとdefaultへ落とさない() {
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        BusinessException ex = assertThrows(BusinessException.class,
                AccountingTenantContextHolder::requireTenantContext);
        assertEquals(403, ex.getCode());
        assertEquals("TENANT_CONTEXT_REQUIRED", ex.getMessage());
    }

    private void authenticate(String tenantId, String role, Long userId) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("admin");
        user.setRole(role);
        LoginUser principal = tenantId == null
                ? new LoginUser(user, List.of())
                : new LoginUser(user, List.of(), tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
