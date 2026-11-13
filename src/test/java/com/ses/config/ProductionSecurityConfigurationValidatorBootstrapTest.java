package com.ses.config;

import com.ses.entity.IdentityProvider;
import com.ses.entity.SysUser;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
import com.ses.mapper.UserMfaMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 期限付きbootstrap mode中だけbreak-glass登録・OIDC管理者の人数の検査を省略し、それ以外の検査は維持し、
 * bootstrapを無効にする（または期限を過ぎる）と完全な検査へ戻ることを検証する。
 * あわせて、tenant-idの常時必須（review P2-B）と、完全な検査の通過時に完了markerを書くこと（review P2-A）を検証する。
 */
class ProductionSecurityConfigurationValidatorBootstrapTest {

    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    private final ProductionBootstrapModeTest.MutableClock clock = new ProductionBootstrapModeTest.MutableClock(NOW);
    private final SysUserMapper sysUserMapper = mock(SysUserMapper.class);
    private final UserMfaMapper userMfaMapper = mock(UserMfaMapper.class);
    private final PermissionGroupMapper permissionGroupMapper = mock(PermissionGroupMapper.class);
    private final IdentityProviderMapper identityProviderMapper = mock(IdentityProviderMapper.class);
    private final UserExternalIdentityMapper userExternalIdentityMapper = mock(UserExternalIdentityMapper.class);
    private final ProductionBootstrapCompletionLatch completionLatch = mock(ProductionBootstrapCompletionLatch.class);

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

        // bootstrap終了の手順どおりbreak-glass管理者2名（MFA登録済み）と、OIDCでログインできる管理者3名を揃えれば通る
        stubCompletedBootstrapUsers(oidc);
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

    // ---------------------------------------------------------------- tenant-id（review P2-B）

    @Test
    void tenant_idが未設定ならbootstrap中も完全な検査でも起動しない() {
        stubFreshInstall();
        for (String tenantId : new String[]{null, "", "  "}) {
            OidcSecurityProperties bootstrapOidc = productionOidc();
            bootstrapOidc.setTenantId(tenantId);
            assertThatThrownBy(validator(bootstrapOidc, bootstrapMode(true))::validate)
                    .hasMessageContaining("OIDCのtenant-id（OIDC_TENANT_ID）を設定してください");

            OidcSecurityProperties fullOidc = productionOidc();
            stubCompletedBootstrapUsers(fullOidc);
            fullOidc.setTenantId(tenantId);
            assertThatThrownBy(validator(fullOidc, bootstrapMode(false))::validate)
                    .hasMessageContaining("OIDCのtenant-id（OIDC_TENANT_ID）を設定してください");
        }
        verify(completionLatch, never()).markCompletedIfAbsent();
    }

    // ---------------------------------------------------------------- OIDC管理者の人数

    @Test
    void OIDCでログインできる管理者がbreak_glass以外に3名未満なら起動しない() {
        stubFreshInstall();
        OidcSecurityProperties oidc = productionOidc();
        stubCompletedBootstrapUsers(oidc);

        // break-glass usernameは数えない（admin・breakglass2を除くと2名）
        stubOidcAdministrators("admin", "breakglass2", "ops-issuer", "ops-approver1");
        assertThatThrownBy(validator(oidc, bootstrapMode(false))::validate)
                .hasMessageContaining("OIDCでログインできる有効な管理者（break-glass username以外）が3名以上必要です")
                .hasMessageContaining("現在2名");

        // 3名いれば通る
        stubOidcAdministrators("admin", "breakglass2", "ops-issuer", "ops-approver1", "ops-approver2");
        assertThatCode(validator(oidc, bootstrapMode(false))::validate).doesNotThrowAnyException();

        // bootstrap中は人数を見ない（これから作る）
        OidcSecurityProperties bootstrapOidc = productionOidc();
        stubOidcAdministrators();
        assertThatCode(validator(bootstrapOidc, bootstrapMode(true))::validate).doesNotThrowAnyException();
    }

    @Test
    void 設定issuerの有効なOIDC_providerが無ければ完全な検査で起動しない() {
        stubFreshInstall();
        OidcSecurityProperties oidc = productionOidc();
        stubCompletedBootstrapUsers(oidc);
        when(identityProviderMapper.selectEnabledByTenantAndIssuer("default", "https://idp.example/tenant/v2.0"))
                .thenReturn(null);

        assertThatThrownBy(validator(oidc, bootstrapMode(false))::validate)
                .hasMessageContaining("有効なOIDC providerが登録されていません");
    }

    // ---------------------------------------------------------------- 完了marker（review P2-A）

    @Test
    void bootstrapを無効にした完全な検査が通ったら完了markerを書きbootstrap中は書かない() {
        stubFreshInstall();
        assertThatCode(validator(productionOidc(), bootstrapMode(true))::validate).doesNotThrowAnyException();
        verify(completionLatch, never()).markCompletedIfAbsent();

        OidcSecurityProperties oidc = productionOidc();
        stubCompletedBootstrapUsers(oidc);
        ProductionSecurityConfigurationValidator validator = validator(oidc, bootstrapMode(false));
        validator.validate();
        validator.validate();
        verify(completionLatch, times(2)).markCompletedIfAbsent();

        // 完全な検査が通らなければ書かない
        org.mockito.Mockito.clearInvocations(completionLatch);
        when(userMfaMapper.countEnrolled("default", 2L)).thenReturn(0);
        assertThatThrownBy(validator::validate).hasMessageContaining("MFA enrollment完了済み");
        verify(completionLatch, never()).markCompletedIfAbsent();
    }

    @Test
    void 完了markerを書けなければ起動を拒否し失敗を監査する() {
        stubFreshInstall();
        OidcSecurityProperties oidc = productionOidc();
        stubCompletedBootstrapUsers(oidc);
        doThrow(new IllegalStateException("db unavailable")).when(completionLatch).markCompletedIfAbsent();

        assertThatThrownBy(validator(oidc, bootstrapMode(false))::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[bootstrap] 初回bootstrapの完了markerを記録できないため起動しません");
        verify(completionLatch).recordCompletionFailure();

        // latch自体が無い場合も起動しない（fail-closed）
        ProductionSecurityConfigurationValidator withoutLatch = validator(oidc, bootstrapMode(false));
        withoutLatch.setBootstrapCompletionLatch(null);
        assertThatThrownBy(withoutLatch::validate).hasMessageContaining("完了markerを記録できない");
    }

    // ---------------------------------------------------------------- helper

    /**
     * bootstrap終了後の状態: break-glass管理者2名（admin・breakglass2、MFA登録済み）と、
     * OIDCでログインできる管理者3名（起票者1名・承認者2名）。
     */
    private void stubCompletedBootstrapUsers(OidcSecurityProperties oidc) {
        oidc.setBreakGlassUsernames(Set.of("admin", "breakglass2"));
        when(sysUserMapper.selectByUsername("breakglass2")).thenReturn(user(2L, "breakglass2", "管理者", 1));
        when(userMfaMapper.countEnrolled("default", 1L)).thenReturn(1);
        when(userMfaMapper.countEnrolled("default", 2L)).thenReturn(1);
        IdentityProvider provider = new IdentityProvider();
        provider.setId(7L);
        provider.setTenantId("default");
        provider.setProviderType("OIDC");
        provider.setIssuerUri("https://idp.example/tenant/v2.0");
        provider.setEnabled(1);
        when(identityProviderMapper.selectEnabledByTenantAndIssuer("default", "https://idp.example/tenant/v2.0"))
                .thenReturn(provider);
        stubOidcAdministrators("ops-issuer", "ops-approver1", "ops-approver2");
    }

    private void stubOidcAdministrators(String... usernames) {
        when(userExternalIdentityMapper.selectApprovedActiveUsernamesByRole("default", 7L, "管理者"))
                .thenReturn(List.of(usernames));
    }

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
        validator.setBootstrapCompletionLatch(completionLatch);
        validator.setIdentityProviderMapper(identityProviderMapper);
        validator.setUserExternalIdentityMapper(userExternalIdentityMapper);
        return validator;
    }

    private ProductionBootstrapMode bootstrapMode(boolean enabled) {
        ProductionBootstrapProperties properties = new ProductionBootstrapProperties();
        properties.setEnabled(enabled);
        properties.setExpiresAt(enabled ? "2026-11-13T20:00:00+09:00" : null);
        ProductionBootstrapMode mode = new ProductionBootstrapMode(properties, true, clock,
                ProductionBootstrapModeTest.notCompletedLatch());
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
