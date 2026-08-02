package com.ses.config;

import com.ses.common.exception.BusinessException;
import com.ses.service.AuditLogService;
import com.ses.service.security.AccountLockService;
import com.ses.service.security.BreakGlassService;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R3-001: session発行結果とlogin成功監査の順序を固定する。 */
class LoginSuccessHandlerAuditTest {

    @Test
    void session登録失敗時は成功監査もredirectもしない() {
        Fixture fixture = fixture();
        doThrow(new BusinessException("session登録失敗"))
                .when(fixture.persistentSessionService).register(any(), any());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(BusinessException.class, () -> fixture.handler.onAuthenticationSuccess(
                new MockHttpServletRequest(), response, fixture.authentication));

        verify(fixture.auditLogService, never()).record(any(), any(), any(), anyInt(), any(), anyBoolean());
        assertEquals(200, response.getStatus());
        assertEquals(null, response.getRedirectedUrl());
    }

    @Test
    void session登録成功後だけ成功監査を一度記録する() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, fixture.authentication);

        verify(fixture.auditLogService).record("user", "AUTH", "/login", 200, "LOGIN_SUCCESS", true);
        assertEquals("/", response.getRedirectedUrl());
    }

    private Fixture fixture() {
        AccountLockService accountLockService = mock(AccountLockService.class);
        MfaService mfaService = mock(MfaService.class);
        PersistentSessionService persistentSessionService = mock(PersistentSessionService.class);
        OidcSecurityProperties oidcProperties = new OidcSecurityProperties();
        BreakGlassService breakGlassService = mock(BreakGlassService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("user", "password", List.of());
        when(mfaService.isRequired(authentication)).thenReturn(false);
        LoginSuccessHandler handler = new LoginSuccessHandler(
                accountLockService, mfaService, persistentSessionService, oidcProperties, breakGlassService);
        ReflectionTestUtils.setField(handler, "auditLogService", auditLogService);
        return new Fixture(handler, persistentSessionService, auditLogService, authentication);
    }

    private record Fixture(LoginSuccessHandler handler,
                           PersistentSessionService persistentSessionService,
                           AuditLogService auditLogService,
                           UsernamePasswordAuthenticationToken authentication) {
    }
}
