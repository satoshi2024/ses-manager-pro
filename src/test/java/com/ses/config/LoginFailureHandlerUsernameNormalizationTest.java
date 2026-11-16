package com.ses.config;

import com.ses.service.AuditLogService;
import com.ses.service.security.AccountLockService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DOC-FINAL delta review N-1: 失敗の計数・bootstrap の判定・監査は、Spring Security の
 * {@code UsernamePasswordAuthenticationFilter} が認証に使う値（入力の {@link String#trim()}）で行う。入力値のままだと、
 * 先頭に空白やタブを付けた名前で認証は同じ口座のパスワードを照合する一方、計数は空振りして lock を回避できた。
 */
class LoginFailureHandlerUsernameNormalizationTest {

    private final AccountLockService accountLockService = mock(AccountLockService.class);

    @Test
    void 前後の空白やタブを付けたusernameの失敗も認証と同じtrim後の名前で数える() throws Exception {
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);

        for (String raw : new String[]{" admin", "admin ", "\tadmin", " \tadmin\r\n"}) {
            handler.onAuthenticationFailure(loginRequest(raw), new MockHttpServletResponse(),
                    new BadCredentialsException("bad credentials"));
        }

        verify(accountLockService, times(4)).onLoginFailure("admin");
        verify(accountLockService, never()).onLoginFailure(" admin");
        verify(accountLockService, never()).onLoginFailure("\tadmin");
    }

    @Test
    void 空白だけのusernameは数えない() throws Exception {
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);

        handler.onAuthenticationFailure(loginRequest(" \t "), new MockHttpServletResponse(),
                new BadCredentialsException("bad credentials"));

        verify(accountLockService, never()).onLoginFailure(anyString());
    }

    @Test
    void bootstrap中は空白やタブ付きのadminの失敗もBOOTSTRAPの監査区分で記録する() throws Exception {
        AuditLogService audit = mock(AuditLogService.class);
        ProductionBootstrapMode bootstrap = mock(ProductionBootstrapMode.class);
        when(bootstrap.isRequested()).thenReturn(true);
        when(bootstrap.isActive()).thenReturn(true);
        when(bootstrap.isBootstrapUsername(anyString())).thenCallRealMethod();
        LoginFailureHandler handler = new LoginFailureHandler(accountLockService);
        ReflectionTestUtils.setField(handler, "auditLogService", audit);
        ReflectionTestUtils.setField(handler, "productionBootstrapMode", bootstrap);

        handler.onAuthenticationFailure(loginRequest(" admin"), new MockHttpServletResponse(),
                new BadCredentialsException("bad credentials"));
        handler.onAuthenticationFailure(loginRequest("\tadmin"), new MockHttpServletResponse(),
                new LockedException("locked"));

        verify(audit).record("admin", "AUTH", "/login", 401, "BOOTSTRAP_LOGIN_FAILURE", false);
        verify(audit).record("admin", "AUTH", "/login", 423, "BOOTSTRAP_LOGIN_LOCKED", false);
        verify(audit, never()).record(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq("LOGIN_FAILURE"),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(accountLockService).onLoginFailure("admin");

        when(bootstrap.isActive()).thenReturn(false);
        handler.onAuthenticationFailure(loginRequest("admin "), new MockHttpServletResponse(),
                new BadCredentialsException("expired"));
        verify(audit).record("admin", "AUTH", "/login", 401, "BOOTSTRAP_LOGIN_EXPIRED", false);
    }

    private static MockHttpServletRequest loginRequest(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setParameter("username", username);
        return request;
    }
}
