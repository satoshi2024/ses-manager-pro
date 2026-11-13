package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserMfaMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 期限付きbootstrap mode中だけbreak-glass登録の検査を省略し、それ以外の検査は維持し、
 * bootstrapを無効にする（または期限を過ぎる）と完全な検査へ戻ることを検証する。
 */
class ProductionSecurityConfigurationValidatorBootstrapTest {

    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    private final ProductionBootstrapModeTest.MutableClock clock = new ProductionBootstrapModeTest.MutableClock(NOW);
    private final SysUserMapper sysUserMapper = mock(SysUserMapper.class);
    private final UserMfaMapper userMfaMapper = mock(UserMfaMapper.class);
    private final PermissionGroupMapper permissionGroupMapper = mock(PermissionGroupMapper.class);

    @Test
    void bootstrap中はbreak_glass未登録の空DBでも起動検査を通す() {
        OidcSecurityProperties oidc = productionOidc();
        ProductionSecurityConfigurationValidator validator = validator(oidc, bootstrapMode(true));
        stubFreshInstall();

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void bootstrapを無効にすると完全な検査へ戻る() {
        OidcSecurityProperties oidc = productionOidc();
        stubFreshInstall();

        for (ProductionBootstrapMode disabled : new ProductionBootstrapMode[]{bootstrapMode(false), null}) {
            assertThatThrownBy(validator(oidc, disabled)::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("break-glass管理者usernameをちょうど2件");
        }

        // bootstrap終了の手順どおりbreak-glass管理者2名（MFA登録済み）を設定すれば通る
        oidc.setBreakGlassUsernames(Set.of("admin", "breakglass2"));
        when(sysUserMapper.selectByUsername("breakglass2")).thenReturn(user(2L, "breakglass2", "管理者", 1));
        when(userMfaMapper.countEnrolled("default", 1L)).thenReturn(1);
        when(userMfaMapper.countEnrolled("default", 2L)).thenReturn(1);
        assertThatCode(validator(oidc, bootstrapMode(false))::validate).doesNotThrowAnyException();

        // MFA未登録のbreak-glass管理者は完全な検査で拒否する
        when(userMfaMapper.countEnrolled("default", 2L)).thenReturn(0);
        assertThatThrownBy(validator(oidc, bootstrapMode(false))::validate)
                .hasMessageContaining("MFA enrollment完了済み");
    }

    @Test
    void 期限を過ぎたbootstrapは完全な検査になる() {
        OidcSecurityProperties oidc = productionOidc();
        stubFreshInstall();
        ProductionBootstrapMode mode = bootstrapMode(true);
        clock.set(Instant.parse("2026-11-13T12:00:00Z"));

        assertThatThrownBy(validator(oidc, mode)::validate)
                .hasMessageContaining("break-glass管理者usernameをちょうど2件");
    }

    @Test
    void bootstrap中もbreak_glass以外の検査は省略しない() {
        stubFreshInstall();

        OidcSecurityProperties localLogin = productionOidc();
        localLogin.setLocalLoginEnabled(true);
        assertThatThrownBy(validator(localLogin, bootstrapMode(true))::validate)
                .hasMessageContaining("通常local loginを無効化してください");

        OidcSecurityProperties noOidc = productionOidc();
        noOidc.setEnabled(false);
        noOidc.setClientSecret(null);
        assertThatThrownBy(validator(noOidc, bootstrapMode(true))::validate)
                .hasMessageContaining("OIDCを有効化してください")
                .hasMessageContaining("client credential");

        OidcSecurityProperties httpIssuer = productionOidc();
        httpIssuer.setTokenUri("http://idp.example/token");
        assertThatThrownBy(validator(httpIssuer, bootstrapMode(true))::validate)
                .hasMessageContaining("token-uri");

        assertThatThrownBy(validator(productionOidc(), bootstrapMode(true), new MfaSecurityProperties())::validate)
                .hasMessageContaining("MFA current encryption key");
    }

    @Test
    void bootstrap中にbreak_glass_usernameを設定すると起動しない() {
        OidcSecurityProperties oidc = productionOidc();
        oidc.setBreakGlassUsernames(Set.of("admin", "breakglass2"));
        stubFreshInstall();

        assertThatThrownBy(validator(oidc, bootstrapMode(true))::validate)
                .hasMessageContaining("bootstrap中はbreak-glass usernameを設定しないでください");
    }

    @Test
    void bootstrap中に初期管理者が無効なら起動しない() {
        OidcSecurityProperties oidc = productionOidc();
        stubFreshInstall();
        when(sysUserMapper.selectByUsername("admin")).thenReturn(user(1L, "admin", "管理者", 0));

        assertThatThrownBy(validator(oidc, bootstrapMode(true))::validate)
                .hasMessageContaining("bootstrap用の初期管理者(admin)");
    }

    // ---------------------------------------------------------------- helper

    /** 空DBへの新規導入直後: adminだけが居てMFA登録は無い。default permission groupはV64のseedで揃う。 */
    private void stubFreshInstall() {
        when(sysUserMapper.selectByUsername(anyString())).thenReturn(null);
        when(sysUserMapper.selectByUsername("admin")).thenReturn(user(1L, "admin", "管理者", 1));
        when(userMfaMapper.countEnrolled(anyString(), anyLong())).thenReturn(0);
        when(permissionGroupMapper.selectCount(any())).thenReturn(1L);
    }

    private ProductionSecurityConfigurationValidator validator(OidcSecurityProperties oidc,
                                                               ProductionBootstrapMode mode) {
        MfaSecurityProperties mfa = new MfaSecurityProperties();
        mfa.setEncryptionKey("01234567890123456789012345678901");
        return validator(oidc, mode, mfa);
    }

    private ProductionSecurityConfigurationValidator validator(OidcSecurityProperties oidc,
                                                               ProductionBootstrapMode mode,
                                                               MfaSecurityProperties mfa) {
        ProductionSecurityConfigurationValidator validator = new ProductionSecurityConfigurationValidator(
                oidc, mfa, session(), sysUserMapper, userMfaMapper, permissionGroupMapper, new AiConfig(),
                new com.ses.common.security.OutboundUrlGuard());
        validator.setProductionBootstrapMode(mode);
        return validator;
    }

    private ProductionBootstrapMode bootstrapMode(boolean enabled) {
        ProductionBootstrapProperties properties = new ProductionBootstrapProperties();
        properties.setEnabled(enabled);
        properties.setExpiresAt(enabled ? "2026-11-13T20:00:00+09:00" : null);
        ProductionBootstrapMode mode = new ProductionBootstrapMode(properties, true, clock);
        mode.validateAtStartup();
        return mode;
    }

    private static PersistentSessionProperties session() {
        PersistentSessionProperties session = new PersistentSessionProperties();
        session.setAllowUntrackedSessions(false);
        session.setHashKey("abcdefghijklmnopqrstuvwxyz123456");
        return session;
    }

    private static OidcSecurityProperties productionOidc() {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setEnabled(true);
        oidc.setLocalLoginEnabled(false);
        oidc.setBreakGlassLoginEnabled(true);
        oidc.setTenantId("default");
        oidc.setIssuerUri("https://idp.example/tenant/v2.0");
        oidc.setAuthorizationUri("https://idp.example/authorize");
        oidc.setTokenUri("https://idp.example/token");
        oidc.setJwkSetUri("https://idp.example/jwks");
        oidc.setUserInfoUri("https://idp.example/userinfo");
        oidc.setClientId("client");
        oidc.setClientSecret("test-only-client-credential");
        return oidc;
    }

    private static SysUser user(Long id, String username, String role, int status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setStatus(status);
        user.setTenantId("default");
        return user;
    }
}
