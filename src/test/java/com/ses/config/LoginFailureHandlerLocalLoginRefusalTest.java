package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.AccountLockService;
import com.ses.service.security.BreakGlassService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-P3-1: local login が許されていない利用者への拒否（パスワード照合前）を失敗回数に数えないこと、
 * パスワードを照合する経路の失敗は従来どおり数えることを、実 {@link DaoAuthenticationProvider} が投げる例外で固定する。
 */
class LoginFailureHandlerLocalLoginRefusalTest {

    private static final String OIDC_ADMIN = "oidc-admin";
    private static final String BREAK_GLASS = "bg-operator";

    private final SysUserMapper sysUserMapper = mock(SysUserMapper.class);
    private final BreakGlassService breakGlassService = mock(BreakGlassService.class);
    private final AccountLockService accountLockService = mock(AccountLockService.class);

    @Test
    void local_login無効の利用者への拒否は何回送られても失敗回数に数えない() throws Exception {
        DaoAuthenticationProvider provider = provider(false);
        when(sysUserMapper.selectByUsername(OIDC_ADMIN)).thenReturn(user(OIDC_ADMIN, "Correct2026x"));
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);

        for (int attempt = 0; attempt < 10; attempt++) {
            // 正しいパスワードでも誤りでも、パスワード照合前に拒否される
            String password = attempt % 2 == 0 ? "wrong-" + attempt : "Correct2026x";
            AuthenticationException failure = authenticate(provider, OIDC_ADMIN, password);
            assertThat(failure).as("DaoAuthenticationProvider は loadUserByUsername の例外を包む")
                    .isInstanceOf(InternalAuthenticationServiceException.class)
                    .hasCauseInstanceOf(LocalLoginDisabledException.class);
            assertThat(LocalLoginDisabledException.isCauseOf(failure)).isTrue();

            MockHttpServletResponse response = new MockHttpServletResponse();
            handler.onAuthenticationFailure(loginRequest(OIDC_ADMIN), response, failure);
            assertThat(response.getRedirectedUrl()).as("遷移先は他の失敗と同じ").isEqualTo("/login?error");
        }

        verify(accountLockService, never()).onLoginFailure(anyString());
    }

    @Test
    void break_glassのパスワード誤りは従来どおり毎回数える() throws Exception {
        DaoAuthenticationProvider provider = provider(false);
        when(sysUserMapper.selectByUsername(BREAK_GLASS)).thenReturn(user(BREAK_GLASS, "BreakGlass2026x"));
        when(breakGlassService.isLoginAllowed(BREAK_GLASS)).thenReturn(true);
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);

        for (int attempt = 0; attempt < 5; attempt++) {
            AuthenticationException failure = authenticate(provider, BREAK_GLASS, "wrong-" + attempt);
            assertThat(failure).isInstanceOf(BadCredentialsException.class);
            assertThat(LocalLoginDisabledException.isCauseOf(failure)).isFalse();
            handler.onAuthenticationFailure(loginRequest(BREAK_GLASS), new MockHttpServletResponse(), failure);
        }

        verify(accountLockService, times(5)).onLoginFailure(BREAK_GLASS);
    }

    @Test
    void break_glass判定を照合順序に合わせても_表記違いのbreak_glassは照合して数えOIDC管理者は数えない() throws Exception {
        // MySQL の utf8mb4_unicode_ci では表記違いの入力も同じ行へ解決される（mapper の mock で再現する）。break-glass 判定
        // （OidcSecurityProperties.isBreakGlassUsername）を区別しない比較にした後も（X3-2）、break-glass 口座は正確な名前と
        // 同じくパスワードを照合して数え、OIDC 管理者の表記違いはパスワード照合前に拒否して数えない（D-P3-1 の防護を弱めない）。
        OidcSecurityProperties breakGlassNames = new OidcSecurityProperties();
        breakGlassNames.setBreakGlassUsernames(java.util.Set.of(BREAK_GLASS));
        when(breakGlassService.isLoginAllowed(anyString()))
                .thenAnswer(invocation -> breakGlassNames.isBreakGlassUsername(invocation.getArgument(0)));
        when(sysUserMapper.selectByUsername("BG-Operator")).thenReturn(user(BREAK_GLASS, "BreakGlass2026x"));
        when(sysUserMapper.selectByUsername("OIDC-Admin")).thenReturn(user(OIDC_ADMIN, "Correct2026x"));
        DaoAuthenticationProvider provider = provider(false);
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);

        for (int attempt = 0; attempt < 3; attempt++) {
            AuthenticationException breakGlassFailure = authenticate(provider, "BG-Operator", "wrong-" + attempt);
            assertThat(breakGlassFailure).isInstanceOf(BadCredentialsException.class);
            handler.onAuthenticationFailure(loginRequest("BG-Operator"), new MockHttpServletResponse(), breakGlassFailure);

            AuthenticationException oidcFailure = authenticate(provider, "OIDC-Admin", "Correct2026x");
            assertThat(LocalLoginDisabledException.isCauseOf(oidcFailure)).isTrue();
            handler.onAuthenticationFailure(loginRequest("OIDC-Admin"), new MockHttpServletResponse(), oidcFailure);
        }

        verify(accountLockService, times(3)).onLoginFailure("BG-Operator");
        verify(accountLockService, never()).onLoginFailure("OIDC-Admin");
    }

    @Test
    void local_login有効の利用者のパスワード誤りは従来どおり数える() throws Exception {
        DaoAuthenticationProvider provider = provider(true);
        when(sysUserMapper.selectByUsername("local-user")).thenReturn(user("local-user", "Local2026x"));
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);

        AuthenticationException failure = authenticate(provider, "local-user", "wrong");
        assertThat(failure).isInstanceOf(BadCredentialsException.class);
        handler.onAuthenticationFailure(loginRequest("local-user"), new MockHttpServletResponse(), failure);

        verify(accountLockService).onLoginFailure("local-user");
    }

    @Test
    void 汎用のDisabledExceptionとロック中の扱いは変えない() throws Exception {
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);

        // 利用停止（status=0）などの DisabledException は従来どおり数える（本修正の対象は local login 拒否だけ）
        handler.onAuthenticationFailure(loginRequest("disabled-user"), new MockHttpServletResponse(),
                new DisabledException("User is disabled"));
        verify(accountLockService).onLoginFailure("disabled-user");

        MockHttpServletResponse locked = new MockHttpServletResponse();
        handler.onAuthenticationFailure(loginRequest("locked-user"), locked, new LockedException("locked"));
        assertThat(locked.getRedirectedUrl()).isEqualTo("/login?locked");
        verify(accountLockService, never()).onLoginFailure("locked-user");
    }

    @Test
    void bootstrap有効期間中のadminはパスワードを照合し誤りを従来どおり数える() throws Exception {
        ProductionBootstrapMode bootstrap = mock(ProductionBootstrapMode.class);
        when(bootstrap.isBootstrapLoginAllowed(eq("admin"), any())).thenReturn(true);
        DaoAuthenticationProvider provider = provider(false, bootstrap);
        when(sysUserMapper.selectByUsername("admin")).thenReturn(user("admin", "Bootstrap2026x"));
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);

        AuthenticationException failure = authenticate(provider, "admin", "wrong");
        assertThat(failure).isInstanceOf(BadCredentialsException.class);
        assertThat(LocalLoginDisabledException.isCauseOf(failure)).isFalse();
        handler.onAuthenticationFailure(loginRequest("admin"), new MockHttpServletResponse(), failure);

        verify(accountLockService).onLoginFailure("admin");
    }

    @Test
    void bootstrap期限切れのadminはパスワード照合前に拒否し数えない() throws Exception {
        ProductionBootstrapMode bootstrap = mock(ProductionBootstrapMode.class);
        when(bootstrap.isBootstrapLoginAllowed(eq("admin"), any())).thenReturn(false);
        DaoAuthenticationProvider provider = provider(false, bootstrap);
        when(sysUserMapper.selectByUsername("admin")).thenReturn(user("admin", "Bootstrap2026x"));
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);

        for (String password : List.of("wrong", "Bootstrap2026x")) {
            AuthenticationException failure = authenticate(provider, "admin", password);
            assertThat(LocalLoginDisabledException.isCauseOf(failure)).isTrue();
            MockHttpServletResponse response = new MockHttpServletResponse();
            handler.onAuthenticationFailure(loginRequest("admin"), response, failure);
            assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        }

        verify(accountLockService, never()).onLoginFailure(anyString());
    }

    private DaoAuthenticationProvider provider(boolean localLoginEnabled) {
        return provider(localLoginEnabled, null);
    }

    private DaoAuthenticationProvider provider(boolean localLoginEnabled, ProductionBootstrapMode bootstrap) {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setLocalLoginEnabled(localLoginEnabled);
        properties.setTenantId("default");
        CustomUserDetailsService userDetailsService =
                new CustomUserDetailsService(sysUserMapper, properties, breakGlassService);
        if (bootstrap != null) {
            userDetailsService.setProductionBootstrapMode(bootstrap);
        }
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(NoOpPasswordEncoder.getInstance());
        return provider;
    }

    private static AuthenticationException authenticate(DaoAuthenticationProvider provider, String username,
                                                        String password) {
        return catchThrowableOfType(() -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password)),
                AuthenticationException.class);
    }

    private static MockHttpServletRequest loginRequest(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setParameter("username", username);
        return request;
    }

    private static SysUser user(String username, String password) {
        SysUser user = new SysUser();
        user.setId(31L);
        user.setUsername(username);
        user.setPassword(password);
        user.setRole("管理者");
        user.setStatus(1);
        user.setDeletedFlag(0);
        user.setTenantId("default");
        return user;
    }
}
