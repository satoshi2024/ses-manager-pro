package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.service.security.AccountLockService;
import com.ses.service.security.BreakGlassService;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 配備決定-3: 初回パスワード変更要求をlogin成功時にsessionへ写し、変更画面へ誘導する。 */
class LoginSuccessHandlerPasswordChangeTest {

    @Test
    void 変更要求のあるlocal_loginは変更画面へ誘導しsessionへ遮断属性を立てる() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(false).onAuthenticationSuccess(request, response, localLogin(1));

        assertThat(response.getRedirectedUrl()).isEqualTo(PasswordChangeEnforcementFilter.CHANGE_PAGE);
        assertThat(request.getSession().getAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void MFAが必要ならMFAを先に完了させる() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(true).onAuthenticationSuccess(request, response, localLogin(1));

        assertThat(response.getRedirectedUrl()).startsWith("/mfa/");
        assertThat(request.getSession().getAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        assertThat(request.getSession().getAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE))
                .as("MFA完了後にPasswordChangeEnforcementFilterが変更画面へ誘導する")
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void 変更要求の無いユーザーは従来どおり() throws Exception {
        for (Integer flag : new Integer[]{0, null}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            handler(false).onAuthenticationSuccess(request, response, localLogin(flag));

            assertThat(response.getRedirectedUrl()).isEqualTo("/");
            assertThat(request.getSession(false) == null ? null : request.getSession(false)
                    .getAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE)).isNull();
        }
    }

    @Test
    void OIDC_loginではlocal_passwordの変更を要求しない() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        DefaultOAuth2User oidcUser = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_管理者")), Map.of("sub", "oidc-admin"), "sub");

        handler(false).onAuthenticationSuccess(request, response,
                new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "oidc"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(request.getSession(false) == null ? null : request.getSession(false)
                .getAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE)).isNull();
    }

    private static LoginSuccessHandler handler(boolean mfaRequired) {
        MfaService mfaService = mock(MfaService.class);
        when(mfaService.isRequired(any())).thenReturn(mfaRequired);
        when(mfaService.isConfigured(any())).thenReturn(false);
        return new LoginSuccessHandler(mock(AccountLockService.class), mfaService,
                mock(PersistentSessionService.class), new OidcSecurityProperties(), mock(BreakGlassService.class));
    }

    private static UsernamePasswordAuthenticationToken localLogin(Integer passwordChangeRequired) {
        SysUser user = new SysUser();
        user.setId(10L);
        user.setUsername("admin");
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId("default");
        user.setPasswordChangeRequired(passwordChangeRequired);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者")), "default");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
