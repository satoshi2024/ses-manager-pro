package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.SysUser;
import com.ses.mapper.AttendanceScopeMapper;
import com.ses.service.accounting.AccountingTimezoneResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 認証requestのtenantを正本とし、未束縛tenantやdefault推測を拒否する。 */
class LegalEntityContextServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T15:00:00Z"), ZoneId.of("UTC"));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 認証tenantを明示的にresolveし法人を一意に解決する() {
        AttendanceScopeMapper mapper = mock(AttendanceScopeMapper.class);
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId("tenant-a");
        when(timezoneResolver.resolve("tenant-a")).thenReturn(ZoneId.of("America/New_York"));
        when(mapper.selectAllLegalEntityIds()).thenReturn(List.of(42L));
        authenticate("tenant-a");

        LegalEntityContextService service = new LegalEntityContextService(clock, mapper, timezoneResolver, properties);

        assertEquals(42L, service.requireCurrentLegalEntityId());
        verify(timezoneResolver).resolve("tenant-a");
    }

    @Test
    void tenant未束縛のprincipalはAccountingTenantContextHolderやdefaultへfallbackしない() {
        authenticate(null);
        LegalEntityContextService service = new LegalEntityContextService(clock,
                mock(AttendanceScopeMapper.class), mock(AccountingTimezoneResolver.class), new OidcSecurityProperties());

        assertThrows(BusinessException.class, service::requireTenantId);
    }

    @Test
    void 認証tenantとdeploymentBindingが違えば拒否する() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId("tenant-a");
        authenticate("tenant-b");
        LegalEntityContextService service = new LegalEntityContextService(clock,
                mock(AttendanceScopeMapper.class), mock(AccountingTimezoneResolver.class), properties);

        assertThrows(BusinessException.class, service::requireTenantId);
    }

    private void authenticate(String tenantId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("管理者");
        LoginUser principal = tenantId == null
                ? new LoginUser(user, List.of())
                : new LoginUser(user, List.of(), tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
