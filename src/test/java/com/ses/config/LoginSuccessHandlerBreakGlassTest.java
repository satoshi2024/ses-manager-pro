package com.ses.config;

import com.ses.service.security.AccountLockService;
import com.ses.service.security.BreakGlassService;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginSuccessHandlerBreakGlassTest {

    @Test
    void 重要監査serviceが利用不能ならbreakGlassLoginを成立させない() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setBreakGlassUsernames(Set.of("BG-01"));
        BreakGlassService breakGlassService = mock(BreakGlassService.class);
        when(breakGlassService.isLoginAllowed("BG-01")).thenReturn(true);
        LoginSuccessHandler handler = new LoginSuccessHandler(
                mock(AccountLockService.class), mock(MfaService.class),
                mock(PersistentSessionService.class), properties, breakGlassService);

        assertThrows(ServletException.class, () -> handler.onAuthenticationSuccess(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                new UsernamePasswordAuthenticationToken("BG-01", "n/a")));
    }
}
