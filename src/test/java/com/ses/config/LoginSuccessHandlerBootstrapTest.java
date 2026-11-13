package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.service.AuditLogService;
import com.ses.service.security.AccountLockService;
import com.ses.service.security.BreakGlassService;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 本番初回bootstrap loginの成功処理（{@link LoginSuccessHandler}）: sessionの印・改密 → MFAの順・期限・監査。 */
class LoginSuccessHandlerBootstrapTest {

    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    private final ProductionBootstrapModeTest.MutableClock clock = new ProductionBootstrapModeTest.MutableClock(NOW);
    private final MfaService mfaService = mock(MfaService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 初回の改密が必要なbootstrap_loginは変更画面へ誘導しMFA_pendingを立てない() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler(mode(true, true)).onAuthenticationSuccess(request, response, localLogin("admin", 1));

        assertThat(response.getRedirectedUrl()).isEqualTo(PasswordChangeEnforcementFilter.CHANGE_PAGE);
        assertThat(request.getSession().getAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        assertThat(request.getSession().getAttribute(ProductionBootstrapMode.SESSION_USER_ATTRIBUTE))
                .as("印はbootstrap loginの本人のuser idへ結び付ける（review P3-a）").isEqualTo(10L);
        assertThat(request.getSession().getAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
        assertThat(request.getSession().getAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE)).isNull();
        verify(auditLogService).recordRequired("admin", "AUTH", "/login", 200, "BOOTSTRAP_LOGIN_SUCCESS", true);
        verify(auditLogService, never()).record(anyString(), anyString(), anyString(), anyInt(),
                eq("LOGIN_SUCCESS"), anyBoolean());
    }

    @Test
    void 改密済みのbootstrap_loginはMFAの登録または検証へ誘導する() throws Exception {
        when(mfaService.isConfigured(any())).thenReturn(false);
        MockHttpServletRequest setupRequest = new MockHttpServletRequest();
        MockHttpServletResponse setup = new MockHttpServletResponse();
        authenticate(localLogin("admin", 0));
        handler(mode(true, true)).onAuthenticationSuccess(setupRequest, setup, localLogin("admin", 0));
        assertThat(setup.getRedirectedUrl()).isEqualTo("/mfa/setup");

        when(mfaService.isConfigured(any())).thenReturn(true);
        MockHttpServletRequest challengeRequest = new MockHttpServletRequest();
        MockHttpServletResponse challenge = new MockHttpServletResponse();
        handler(mode(true, true)).onAuthenticationSuccess(challengeRequest, challenge, localLogin("admin", 0));
        assertThat(challenge.getRedirectedUrl()).isEqualTo("/mfa/challenge");
        assertThat(challengeRequest.getSession().getAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void 認証後に期限を過ぎたbootstrap_loginはsessionを破棄して拒否し監査する() {
        ProductionBootstrapMode mode = mode(true, true);
        clock.set(Instant.parse("2026-11-13T12:00:00Z"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        assertThatThrownBy(() -> handler(mode).onAuthenticationSuccess(request, new MockHttpServletResponse(),
                localLogin("admin", 1)))
                .isInstanceOf(ServletException.class);
        assertThat(session.isInvalid()).isTrue();
        verify(auditLogService).record("admin", "AUTH", "/login", 401, "BOOTSTRAP_LOGIN_EXPIRED", false);
        verify(auditLogService, never()).recordRequired(anyString(), anyString(), anyString(), anyInt(),
                eq("BOOTSTRAP_LOGIN_SUCCESS"), anyBoolean());
    }

    @Test
    void prod以外やOIDCやadmin以外のloginはbootstrap_sessionにしない() throws Exception {
        // prod以外: enabledでも従来どおり（変更要求があれば変更画面、LOGIN_SUCCESSを監査）
        MockHttpServletRequest nonProd = new MockHttpServletRequest();
        MockHttpServletResponse nonProdResponse = new MockHttpServletResponse();
        handler(mode(false, true)).onAuthenticationSuccess(nonProd, nonProdResponse, localLogin("admin", 0));
        assertThat(nonProdResponse.getRedirectedUrl()).isEqualTo("/");
        assertThat(nonProd.getSession(false) == null ? null
                : nonProd.getSession(false).getAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE)).isNull();
        verify(auditLogService).record("admin", "AUTH", "/login", 200, "LOGIN_SUCCESS", true);

        // OIDC loginは対象外
        MockHttpServletRequest oidc = new MockHttpServletRequest();
        DefaultOAuth2User oidcUser = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_管理者")), Map.of("sub", "admin"), "sub");
        handler(mode(true, true)).onAuthenticationSuccess(oidc, new MockHttpServletResponse(),
                new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "enterprise"));
        assertThat(oidc.getSession(false) == null ? null
                : oidc.getSession(false).getAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE)).isNull();

        // admin以外のlocal login（通常はUserDetailsServiceで拒否されるが、ここでもbootstrap扱いにしない）
        MockHttpServletRequest other = new MockHttpServletRequest();
        handler(mode(true, true)).onAuthenticationSuccess(other, new MockHttpServletResponse(),
                localLogin("operator", 0));
        assertThat(other.getSession(false) == null ? null
                : other.getSession(false).getAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE)).isNull();
    }

    @Test
    void 同じsessionで別の主体がloginするとbootstrapの印とMFA検証と改密要求を引き継がない() throws Exception {
        // review P3-a: session fixation対策はsession IDだけを替えて属性を残すため、login時に安全状態を消す
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(ProductionBootstrapMode.SESSION_USER_ATTRIBUTE, 10L);
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE, 10L);
        session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, Boolean.TRUE);
        request.setSession(session);
        DefaultOAuth2User oidcUser = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_管理者")), Map.of("sub", "second-admin"), "sub");

        handler(mode(true, true)).onAuthenticationSuccess(request, new MockHttpServletResponse(),
                new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "enterprise"));

        for (String attribute : LoginSuccessHandler.AUTHENTICATION_SCOPED_ATTRIBUTES) {
            assertThat(session.getAttribute(attribute)).as("引き継がない属性: %s", attribute).isNull();
        }
        assertThat(LoginSuccessHandler.AUTHENTICATION_SCOPED_ATTRIBUTES).contains(
                ProductionBootstrapMode.SESSION_ATTRIBUTE, ProductionBootstrapMode.SESSION_USER_ATTRIBUTE,
                MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE, MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE,
                MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE,
                PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE);
    }

    // ---------------------------------------------------------------- helper

    private LoginSuccessHandler handler(ProductionBootstrapMode mode) {
        when(mfaService.isRequired(any())).thenReturn(false);
        LoginSuccessHandler handler = new LoginSuccessHandler(mock(AccountLockService.class), mfaService,
                mock(PersistentSessionService.class), new OidcSecurityProperties(), mock(BreakGlassService.class));
        ReflectionTestUtils.setField(handler, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(handler, "productionBootstrapMode", mode);
        return handler;
    }

    private ProductionBootstrapMode mode(boolean production, boolean enabled) {
        ProductionBootstrapProperties properties = new ProductionBootstrapProperties();
        properties.setEnabled(enabled);
        properties.setExpiresAt("2026-11-13T20:00:00+09:00");
        ProductionBootstrapMode mode = new ProductionBootstrapMode(properties, production, clock,
                ProductionBootstrapModeTest.notCompletedLatch());
        mode.validateAtStartup();
        return mode;
    }

    private static UsernamePasswordAuthenticationToken localLogin(String username, Integer passwordChangeRequired) {
        SysUser user = new SysUser();
        user.setId(10L);
        user.setUsername(username);
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId("default");
        user.setPasswordChangeRequired(passwordChangeRequired);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者")), "default");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static void authenticate(UsernamePasswordAuthenticationToken authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
