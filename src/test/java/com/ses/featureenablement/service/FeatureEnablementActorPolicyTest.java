package com.ses.featureenablement.service;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.MfaEnforcementFilter;
import com.ses.config.OidcLoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.ProductionBootstrapMode;
import com.ses.entity.SysUser;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.BreakGlassService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-A05（design §11.2、FE-AC-003-03）: 機能の開放の行為者は、prod では OIDC の session で MFA を済ませた有効な管理者だけ。
 * break-glass の incident の属性・bootstrap の session・break-glass の username・lock 中・無効・削除済み・管理者以外は 403。
 * prod 以外は local の form login も許す。
 */
class FeatureEnablementActorPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final String TENANT = "default";

    private final SysUserMapper mapper = mock(SysUserMapper.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final OidcSecurityProperties oidc = new OidcSecurityProperties();

    @Test
    void OIDCでMFAを済ませた有効な管理者は可でMFA未済とsession無しは不可() {
        environment.setActiveProfiles("prod");
        SysUser admin = user(1L, "管理者");
        when(mapper.selectByIdAndTenant(1L, TENANT)).thenReturn(admin);
        MockHttpSession session = mfa(1L);

        FeatureEnablementActor actor = policy().resolve(oidcAuthentication(admin), session);
        assertThat(actor.userId()).isEqualTo(1L);

        assertDenied(() -> policy().resolve(oidcAuthentication(admin), new MockHttpSession()));
        assertDenied(() -> policy().resolve(oidcAuthentication(admin), null));
        MockHttpSession otherUser = mfa(2L);
        assertDenied(() -> policy().resolve(oidcAuthentication(admin), otherUser));
    }

    @Test
    void break_glassの属性とbootstrapのsessionとbreak_glassのusernameは不可() {
        environment.setActiveProfiles("prod");
        SysUser admin = user(1L, "管理者");
        when(mapper.selectByIdAndTenant(1L, TENANT)).thenReturn(admin);

        MockHttpSession breakGlass = mfa(1L);
        breakGlass.setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 99L);
        assertDenied(() -> policy().resolve(oidcAuthentication(admin), breakGlass));

        MockHttpSession bootstrap = mfa(1L);
        bootstrap.setAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE, Boolean.TRUE);
        assertDenied(() -> policy().resolve(oidcAuthentication(admin), bootstrap));

        oidc.setBreakGlassUsernames(new java.util.LinkedHashSet<>(List.of("bg-admin")));
        SysUser breakGlassUser = user(1L, "管理者");
        breakGlassUser.setUsername("bg-admin");
        assertDenied(() -> policy().resolve(oidcAuthentication(breakGlassUser), mfa(1L)));
    }

    @Test
    void lock中と無効と削除済みと管理者以外と別tenantは不可() {
        SysUser locked = user(1L, "管理者");
        locked.setLockedUntil(LocalDateTime.ofInstant(NOW, ZoneId.systemDefault()).plusMinutes(5));
        SysUser disabled = user(2L, "管理者");
        disabled.setStatus(0);
        SysUser deleted = user(3L, "管理者");
        deleted.setDeletedFlag(1);
        SysUser sales = user(4L, "営業");
        SysUser otherTenant = user(5L, "管理者");
        otherTenant.setTenantId("tenant-x");
        for (SysUser user : List.of(locked, disabled, deleted, sales, otherTenant)) {
            when(mapper.selectByIdAndTenant(user.getId(), TENANT)).thenReturn(user);
            assertDenied(() -> policy().requireActiveAdministrator(user.getId()));
        }
        assertDenied(() -> policy().requireActiveAdministrator(9L));
        assertDenied(() -> policy().requireActiveAdministrator(null));

        // lock の期限を過ぎていれば可
        SysUser unlocked = user(6L, "管理者");
        unlocked.setLockedUntil(LocalDateTime.ofInstant(NOW, ZoneId.systemDefault()).minusMinutes(1));
        when(mapper.selectByIdAndTenant(6L, TENANT)).thenReturn(unlocked);
        assertThat(policy().requireActiveAdministrator(6L).userId()).isEqualTo(6L);
    }

    @Test
    void prod以外はlocalのform_loginも可でprodでは不可() {
        SysUser admin = user(1L, "管理者");
        when(mapper.selectByIdAndTenant(1L, TENANT)).thenReturn(admin);
        Authentication local = new UsernamePasswordAuthenticationToken(new LoginUser(admin, List.of(new SimpleGrantedAuthority("ROLE_管理者"))), null,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));

        assertThat(policy().resolve(local, new MockHttpSession()).userId()).isEqualTo(1L);

        environment.setActiveProfiles("prod");
        assertDenied(() -> policy().resolve(local, new MockHttpSession()));
        // 未認証・principal の無い認証も不可
        assertDenied(() -> policy().resolve(null, new MockHttpSession()));
    }

    @Test
    void NF07のprivacy_adminの規則は段階Cまで無効で通知の宛先は有効な管理者だけ() {
        assertThat(FeatureEnablementActorPolicy.PRIVACY_ADMIN_RULE_ENABLED).isFalse();
        policy().requirePrivacyAdminIfRequired(true, new FeatureEnablementActor(1L, "admin"));

        SysUser active = user(1L, "管理者");
        SysUser locked = user(2L, "管理者");
        locked.setLockedUntil(LocalDateTime.ofInstant(NOW, ZoneId.systemDefault()).plusHours(1));
        when(mapper.selectActiveByRoleAndTenant("管理者", TENANT)).thenReturn(List.of(active, locked));
        assertThat(policy().activeAdministratorIds()).containsExactly(1L);
    }

    // ---------------------------------------------------------------- helper

    @SuppressWarnings("unchecked")
    private FeatureEnablementActorPolicy policy() {
        ObjectProvider<OidcSecurityProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oidc);
        return new FeatureEnablementActorPolicy(mapper, environment, provider, FeatureEnablementTenant.fixed(TENANT),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SysUser user(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("user" + id);
        user.setRole(role);
        user.setStatus(1);
        user.setDeletedFlag(0);
        user.setTenantId(TENANT);
        return user;
    }

    private static MockHttpSession mfa(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE, userId);
        return session;
    }

    private static Authentication oidcAuthentication(SysUser user) {
        OidcLoginUser principal = mock(OidcLoginUser.class);
        when(principal.getSysUser()).thenReturn(user);
        when(principal.getUsername()).thenReturn(user.getUsername());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_管理者")));
    }

    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        BusinessException denied = catchThrowableOfType(call, BusinessException.class);
        assertThat(denied).isNotNull();
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getMessageKey()).isEqualTo(FeatureEnablementActorPolicy.ACTOR_NOT_ALLOWED);
    }
}
