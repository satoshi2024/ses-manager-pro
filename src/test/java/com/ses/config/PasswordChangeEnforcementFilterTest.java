package com.ses.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 初回パスワード変更までの遮断範囲（配備決定-3）を単体で固定する。 */
class PasswordChangeEnforcementFilterTest {

    private final PasswordChangeEnforcementFilter filter = new PasswordChangeEnforcementFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 変更前は画面を変更画面へredirectする() throws Exception {
        authenticate();
        for (String uri : List.of("/", "/dashboard", "/engineer/list", "/user/list", "/mfa/setup")) {
            MockHttpServletResponse response = run("GET", uri, requiredSession());
            assertThat(response.getRedirectedUrl()).as(uri).isEqualTo("/password/change");
        }
    }

    @Test
    void 変更前はAPIを403のApiResultにする() throws Exception {
        authenticate();
        for (String[] request : new String[][]{
                {"GET", "/api/notifications"}, {"GET", "/api/users"}, {"POST", "/api/engineers"},
                {"GET", "/api/profile/password"}, {"POST", "/api/profile/password"}}) {
            MockHttpServletResponse response = run(request[0], request[1], requiredSession());
            assertThat(response.getStatus()).as(request[0] + " " + request[1]).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("\"code\":403").contains("パスワードの変更が必要です");
        }
    }

    @Test
    void 変更画面_変更API_logout_静的資源_errorは通す() throws Exception {
        authenticate();
        assertPassed(run("GET", "/password/change", requiredSession()));
        assertPassed(run("PUT", "/api/profile/password", requiredSession()));
        assertPassed(run("POST", "/logout", requiredSession()));
        assertPassed(run("GET", "/js/modules/password-change.js", requiredSession()));
        assertPassed(run("GET", "/lib/bootstrap/bootstrap.min.css", requiredSession()));

        MockHttpServletRequest error = request("GET", "/error", requiredSession());
        error.setDispatcherType(DispatcherType.ERROR);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(error, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void MFA未完了の間はMFAの判定に任せる() throws Exception {
        authenticate();
        MockHttpSession session = requiredSession();
        session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);
        assertPassed(run("GET", "/mfa/setup", session));

        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        assertThat(run("GET", "/dashboard", session).getRedirectedUrl())
                .as("MFA完了後は変更画面へ誘導する").isEqualTo("/password/change");
    }

    @Test
    void 遮断属性が無いsessionや未認証は通す() throws Exception {
        authenticate();
        assertPassed(run("GET", "/dashboard", new MockHttpSession()));
        assertPassed(run("GET", "/dashboard", null));

        SecurityContextHolder.clearContext();
        assertPassed(run("GET", "/dashboard", requiredSession()));
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertPassed(run("GET", "/dashboard", requiredSession()));
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
    }

    private static MockHttpSession requiredSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, Boolean.TRUE);
        return session;
    }

    private static MockHttpServletRequest request(String method, String uri, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (session != null) {
            request.setSession(session);
        }
        return request;
    }

    private MockHttpServletResponse run(String method, String uri, MockHttpSession session) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(method, uri, session), response, chain);
        if (chain.getRequest() != null) {
            response.setHeader("X-Test-Passed", "true");
        }
        return response;
    }

    private static void assertPassed(MockHttpServletResponse response) {
        assertThat(response.getHeader("X-Test-Passed")).isEqualTo("true");
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
