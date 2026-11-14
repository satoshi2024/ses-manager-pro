package com.ses.privacy.foundation;

import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import com.ses.entity.SysUser;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.mapper.SysUserMapper;
import com.ses.portal.PortalLoginUser;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityContextCallerContextProviderTest {
    private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
    private LegalEntityContextService legalEntityContext;
    private SysUserMapper sysUserMapper;
    private SecurityContextCallerContextProvider provider;

    @BeforeEach
    void setUp() {
        legalEntityContext = mock(LegalEntityContextService.class);
        sysUserMapper = mock(SysUserMapper.class);
        when(sysUserMapper.selectByIdAndTenant(17L, "tenant-a")).thenReturn(activeAdmin("tenant-a"));
        when(legalEntityContext.requireCurrentLegalEntityId()).thenReturn(42L);
        provider = new SecurityContextCallerContextProvider(legalEntityContext, sysUserMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMissingUnauthenticatedAnonymousAndUnknownPrincipals() {
        assertFalse(provider.getCallerContext().isPresent());

        Authentication unauthenticated = new UsernamePasswordAuthenticationToken("actor", null);
        SecurityContextHolder.getContext().setAuthentication(unauthenticated);
        assertFalse(provider.getCallerContext().isPresent());

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertFalse(provider.getCallerContext().isPresent());

        setAuthentication("unknown", authorities());
        assertFalse(provider.getCallerContext().isPresent());
    }

    @Test
    void acceptsActiveInternalLoginAndUsesIdentityObservationTime() {
        setAuthentication(loginUser(activeAdmin("tenant-a"), authorities(), "tenant-a"), authorities());

        TrustedCallerContext caller = provider.getCallerContext().orElseThrow();
        assertEquals("tenant-a", caller.tenantId());
        assertEquals("17", caller.actorId());
        assertEquals(TrustedActorRole.ROLE_ADMIN, caller.actorRole());
        assertEquals(NOW, caller.authenticatedAt());
    }

    @Test
    void acceptsOidcOnlyWhenInternalTenantAndEveryPresentClaimAgree() {
        OidcUser delegate = mock(OidcUser.class);
        when(delegate.getClaims()).thenReturn(Map.of("tenant_id", "tenant-a", "tenantId", "tenant-a"));
        OidcLoginUser oidc = new OidcLoginUser(activeAdmin("tenant-a"), delegate, authorities());
        setAuthentication(oidc, authorities());

        assertTrue(provider.getCallerContext().isPresent());
    }

    @Test
    void rejectsOidcClaimFallbackMismatchAndConflictingClaims() {
        OidcUser delegate = mock(OidcUser.class);
        when(delegate.getClaims()).thenReturn(Map.of("tenant_id", "tenant-a"));
        setAuthentication(new OidcLoginUser(activeAdmin(null), delegate, authorities()), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        when(delegate.getClaims()).thenReturn(Map.of("tenant_id", "tenant-b"));
        setAuthentication(new OidcLoginUser(activeAdmin("tenant-a"), delegate, authorities()), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        when(delegate.getClaims()).thenReturn(Map.of("tenant_id", "tenant-a", "tenant", "tenant-b"));
        setAuthentication(new OidcLoginUser(activeAdmin("tenant-a"), delegate, authorities()), authorities());
        assertFalse(provider.getCallerContext().isPresent());
    }

    @Test
    void rejectsMissingInvalidOrLockedSysUserEvenWhenAuthenticationIsAuthenticated() {
        setAuthentication(loginUser(null, authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        SysUser disabled = activeAdmin("tenant-a");
        disabled.setStatus(0);
        setAuthentication(loginUser(disabled, authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        SysUser deleted = activeAdmin("tenant-a");
        deleted.setDeletedFlag(1);
        setAuthentication(loginUser(deleted, authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        SysUser locked = activeAdmin("tenant-a");
        locked.setLockedUntil(LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        setAuthentication(loginUser(locked, authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        when(sysUserMapper.selectByIdAndTenant(17L, "tenant-a")).thenReturn(null);
        setAuthentication(loginUser(activeAdmin("tenant-a"), authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        SysUser currentlyDisabled = activeAdmin("tenant-a");
        currentlyDisabled.setStatus(0);
        when(sysUserMapper.selectByIdAndTenant(17L, "tenant-a")).thenReturn(currentlyDisabled);
        setAuthentication(loginUser(activeAdmin("tenant-a"), authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        SysUser currentlyDeleted = activeAdmin("tenant-a");
        currentlyDeleted.setDeletedFlag(1);
        when(sysUserMapper.selectByIdAndTenant(17L, "tenant-a")).thenReturn(currentlyDeleted);
        setAuthentication(loginUser(activeAdmin("tenant-a"), authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        SysUser currentlyLocked = activeAdmin("tenant-a");
        currentlyLocked.setLockedUntil(LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        when(sysUserMapper.selectByIdAndTenant(17L, "tenant-a")).thenReturn(currentlyLocked);
        setAuthentication(loginUser(activeAdmin("tenant-a"), authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());
    }

    @Test
    void rejectsManagerNameWithoutExplicitAdminAndPrivacyAuthorities() {
        List<SimpleGrantedAuthority> ordinaryAdmin = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        setAuthentication(loginUser(activeAdmin("tenant-a"), ordinaryAdmin, "tenant-a"), ordinaryAdmin);
        assertFalse(provider.getCallerContext().isPresent());

        List<SimpleGrantedAuthority> missingPrivacy = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        setAuthentication(loginUser(activeAdmin("tenant-a"), missingPrivacy, "tenant-a"), missingPrivacy);
        assertFalse(provider.getCallerContext().isPresent());

        List<SimpleGrantedAuthority> privacyOnly = List.of(new SimpleGrantedAuthority("PRIVACY_ADMIN_READ"));
        setAuthentication(loginUser(activeAdmin("tenant-a"), privacyOnly, "tenant-a"), privacyOnly);
        assertFalse(provider.getCallerContext().isPresent());
    }

    @Test
    void rejectsNonAdminRolePortalAndAmbiguousLegalEntityContext() {
        SysUser hr = activeAdmin("tenant-a");
        hr.setRole("HR");
        setAuthentication(loginUser(hr, authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        SysUser missingId = activeAdmin("tenant-a");
        missingId.setId(null);
        setAuthentication(loginUser(missingId, authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        setAuthentication(PortalLoginUser.builder().portalUserId(7L).tenantId("tenant-a").build(), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        setAuthentication(new ExternalApiPrincipal("client-1", 1L, "tenant-a", 42L,
                "{}", 1, "key-1", "STANDARD"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        when(legalEntityContext.requireCurrentLegalEntityId()).thenReturn(null);
        setAuthentication(loginUser(activeAdmin("tenant-a"), authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());

        when(legalEntityContext.requireCurrentLegalEntityId()).thenThrow(new IllegalStateException("ambiguous"));
        setAuthentication(loginUser(activeAdmin("tenant-a"), authorities(), "tenant-a"), authorities());
        assertFalse(provider.getCallerContext().isPresent());
    }

    private static SysUser activeAdmin(String tenantId) {
        SysUser sysUser = new SysUser();
        sysUser.setId(17L);
        sysUser.setTenantId(tenantId);
        sysUser.setRole("管理者");
        sysUser.setStatus(1);
        sysUser.setDeletedFlag(0);
        return sysUser;
    }

    private static LoginUser loginUser(SysUser sysUser, List<SimpleGrantedAuthority> granted, String tenantId) {
        return new LoginUser(sysUser, granted, tenantId);
    }

    private static List<SimpleGrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("PRIVACY_ADMIN_READ"));
    }

    private static void setAuthentication(Object principal, List<SimpleGrantedAuthority> granted) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "test-credential", granted));
    }
}
