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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
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

    private DaoAuthenticationProvider provider(boolean localLoginEnabled) {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setLocalLoginEnabled(localLoginEnabled);
        properties.setTenantId("default");
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(new CustomUserDetailsService(sysUserMapper, properties, breakGlassService));
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
