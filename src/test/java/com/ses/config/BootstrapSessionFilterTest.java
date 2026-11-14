package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.service.AuditLogService;
import com.ses.service.security.MfaService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** bootstrap session（{@link BootstrapSessionFilter}）の順序・入口・期限をfilter単体で検証する。 */
class BootstrapSessionFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    private final ProductionBootstrapModeTest.MutableClock clock = new ProductionBootstrapModeTest.MutableClock(NOW);
    private final MfaService mfaService = mock(MfaService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final BootstrapSessionFilter filter = new BootstrapSessionFilter(
            provider(activeMode()), provider(mfaService), provider(auditLogService));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 入口の判定

    @Test
    void bootstrapに必要な入口だけを許す() {
        String[][] allowed = {
                {"GET", "/user/list"}, {"GET", "/api/users"}, {"POST", "/api/users"}, {"PUT", "/api/users/2"},
                {"PUT", "/api/users/2/status"}, {"GET", "/api/role-menus/menus"}, {"GET", "/api/role-menus"},
                {"GET", "/api/permission-groups"}, {"GET", "/api/permission-groups/users/2"},
                {"GET", "/api/identity-providers"}, {"POST", "/api/identity-providers"},
                {"POST", "/api/identity-providers/1/external-identities"},
                {"GET", "/mfa/setup"}, {"GET", "/mfa/challenge"}, {"GET", "/api/security/mfa/status"},
                {"POST", "/api/security/mfa/setup"}, {"POST", "/api/security/mfa/enable"},
                {"POST", "/api/security/mfa/verify"}, {"POST", "/logout"},
                {"GET", "/password/change"}, {"PUT", "/api/profile/password"},
                {"GET", "/js/i18n.js"}, {"GET", "/css/common.css"}, {"GET", "/lib/bootstrap/bootstrap.min.css"},
                {"GET", "/api/autocomplete/users"}};
        for (String[] entry : allowed) {
            assertThat(BootstrapSessionFilter.isBootstrapEntry(new MockHttpServletRequest(entry[0], entry[1])))
                    .as("許可: %s %s", entry[0], entry[1]).isTrue();
        }
    }

    @Test
    void bootstrap以外の画面とAPIは許さない() {
        String[][] denied = {
                {"GET", "/"}, {"GET", "/dashboard"}, {"GET", "/engineer/list"}, {"GET", "/api/engineers"},
                {"POST", "/api/engineers"}, {"DELETE", "/api/engineers/1"}, {"GET", "/api/notifications"},
                {"GET", "/api/system-configs"}, {"GET", "/system-config"}, {"GET", "/api/audit-logs"},
                {"GET", "/api/autocomplete/engineers"}, {"POST", "/api/security/mfa/2/reset"},
                {"POST", "/api/security/break-glass/incidents"}, {"GET", "/api/future-sensitive"},
                {"GET", "/future-sensitive"}, {"POST", "/password/change"}, {"GET", "/api/profile/password"},
                {"POST", "/css/common.css"}, {"GET", "/api/users/export"}};
        for (String[] entry : denied) {
            assertThat(BootstrapSessionFilter.isBootstrapEntry(new MockHttpServletRequest(entry[0], entry[1])))
                    .as("拒否: %s %s", entry[0], entry[1]).isFalse();
        }
    }

    @Test
    void 権限groupとrole別menuは参照だけを許し書込は許さない() {
        // review P3-b: role別menuの全置換と権限groupの割当替えはbootstrapに要らない（作成時に既定groupが割り当たる）
        String[][] denied = {
                {"PUT", "/api/role-menus"}, {"POST", "/api/role-menus"}, {"DELETE", "/api/role-menus"},
                {"PUT", "/api/permission-groups/users/2"}, {"POST", "/api/permission-groups"},
                {"DELETE", "/api/permission-groups/1"}};
        for (String[] entry : denied) {
            assertThat(BootstrapSessionFilter.isBootstrapEntry(new MockHttpServletRequest(entry[0], entry[1])))
                    .as("拒否: %s %s", entry[0], entry[1]).isFalse();
        }
    }

    @Test
    void 別の主体へ引き継がれたbootstrapの印はsessionごと失効させる() throws Exception {
        // review P3-a: 印はbootstrap loginの本人（user id 1）に結び付いている。別のuser idの主体では効かせない
        authenticateAs(2L, "admin");
        MockHttpSession session = bootstrapSession();
        markMfaVerified(session, 2L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("GET", "/api/users", session), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        verify(auditLogService).record(eq("admin"), eq("GET"), eq("/api/users"), eq(401),
                eq("BOOTSTRAP_SESSION_MISMATCH"), eq(false));
    }

    @Test
    void 別の主体のMFA検証済みの印ではbootstrap_sessionのMFA完了にしない() throws Exception {
        authenticate();
        MockHttpSession session = bootstrapSession();
        markMfaVerified(session, 99L);

        MockHttpServletResponse api = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("GET", "/api/users", session), api, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(api.getStatus()).isEqualTo(403);
        assertThat(api.getContentAsString()).contains("MFA認証が必要です");
    }

    // ---------------------------------------------------------------- filterの挙動

    @Test
    void bootstrap_sessionでなければ何もしない() throws Exception {
        authenticate();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/engineers");
        request.setSession(new MockHttpSession());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 改密前は変更経路だけをPasswordChangeEnforcementFilterへ任せて通す() throws Exception {
        authenticate();
        MockHttpSession session = bootstrapSession();
        session.setAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, Boolean.TRUE);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("PUT", "/api/profile/password", session), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("改密前はMFAを待たずに変更APIを通す").isNotNull();
    }

    @Test
    void 改密後はMFA完了までMFA画面へ誘導しAPIを403にする() throws Exception {
        authenticate();
        MockHttpSession session = bootstrapSession();
        when(mfaService.isConfigured(1L)).thenReturn(false);

        MockHttpServletResponse page = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/user/list", session), page, new MockFilterChain());
        assertThat(page.getRedirectedUrl()).isEqualTo("/mfa/setup");

        when(mfaService.isConfigured(1L)).thenReturn(true);
        MockHttpServletResponse challenge = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/", session), challenge, new MockFilterChain());
        assertThat(challenge.getRedirectedUrl()).isEqualTo("/mfa/challenge");

        MockHttpServletResponse api = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/users", session), api, new MockFilterChain());
        assertThat(api.getStatus()).isEqualTo(403);
        assertThat(api.getContentAsString()).contains("MFA認証が必要です");

        MockFilterChain mfaChain = new MockFilterChain();
        filter.doFilter(request("POST", "/api/security/mfa/enable", session), new MockHttpServletResponse(), mfaChain);
        assertThat(mfaChain.getRequest()).as("MFA登録APIは通す").isNotNull();
    }

    @Test
    void MFA完了後は入口だけを通し他は403で監査しrootはユーザー管理へ誘導する() throws Exception {
        authenticate();
        MockHttpSession session = bootstrapSession();
        markMfaVerified(session, 1L);

        MockFilterChain allowed = new MockFilterChain();
        filter.doFilter(request("POST", "/api/users", session), new MockHttpServletResponse(), allowed);
        assertThat(allowed.getRequest()).isNotNull();

        MockHttpServletResponse root = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/", session), root, new MockFilterChain());
        assertThat(root.getRedirectedUrl()).isEqualTo(BootstrapSessionFilter.LANDING_PAGE);

        MockHttpServletResponse api = new MockHttpServletResponse();
        MockFilterChain deniedChain = new MockFilterChain();
        filter.doFilter(request("GET", "/api/engineers", session), api, deniedChain);
        assertThat(deniedChain.getRequest()).isNull();
        assertThat(api.getStatus()).isEqualTo(403);
        assertThat(api.getHeader("X-SES-Error-Code")).isEqualTo("BOOTSTRAP_SCOPE_VIOLATION");
        assertThat(api.getContentAsString()).contains("\"code\":403");
        verify(auditLogService).record(eq("admin"), eq("GET"), eq("/api/engineers"), eq(403),
                eq("BOOTSTRAP_SCOPE_DENIED"), eq(false));

        MockHttpServletResponse page = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/dashboard", session), page, new MockFilterChain());
        assertThat(page.getStatus()).as("画面は統一エラー画面（sendError）").isEqualTo(403);
        assertThat(page.getErrorMessage()).isNull();
    }

    @Test
    void 期限を過ぎたbootstrap_sessionは401で失効させる() throws Exception {
        authenticate();
        MockHttpSession session = bootstrapSession();
        markMfaVerified(session, 1L);
        clock.set(Instant.parse("2026-11-13T12:00:00Z"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("GET", "/api/users", session), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        verify(auditLogService).record(eq("admin"), eq("GET"), eq("/api/users"), eq(401),
                eq("BOOTSTRAP_SESSION_EXPIRED"), eq(false));
    }

    @Test
    void 静的資源とERRORディスパッチは常に通す() throws Exception {
        authenticate();
        MockHttpSession session = bootstrapSession();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("GET", "/js/i18n.js", session), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
        verify(auditLogService, never()).record(anyString(), anyString(), anyString(), anyInt(), anyString(), anyBoolean());

        MockHttpServletRequest error = request("GET", "/error", session);
        error.setDispatcherType(DispatcherType.ERROR);
        assertThat(BootstrapSessionFilter.isBootstrapEntry(error)).isTrue();
    }

    @Test
    void modeが無いcontextではbootstrap_sessionをfail_closedで拒否する() throws Exception {
        authenticate();
        BootstrapSessionFilter withoutMode = new BootstrapSessionFilter(
                provider(null), provider(mfaService), provider(auditLogService));
        MockHttpServletResponse response = new MockHttpServletResponse();
        withoutMode.doFilter(request("GET", "/api/users", bootstrapSession()), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- helper

    private ProductionBootstrapMode activeMode() {
        ProductionBootstrapProperties properties = new ProductionBootstrapProperties();
        properties.setEnabled(true);
        properties.setExpiresAt("2026-11-13T20:00:00+09:00");
        ProductionBootstrapMode mode = new ProductionBootstrapMode(properties, true, clock,
                ProductionBootstrapModeTest.notCompletedLatch());
        mode.validateAtStartup();
        return mode;
    }

    /** bootstrap login（user id 1のadmin）で発行したsession。 */
    private static MockHttpSession bootstrapSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(ProductionBootstrapMode.SESSION_USER_ATTRIBUTE, 1L);
        return session;
    }

    /** 指定user idの本人がMFAを検証した状態にする（{@code SecuritySessionApiController.markVerified} と同じ属性）。 */
    private static void markMfaVerified(MockHttpSession session, Long userId) {
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE, userId);
    }

    private static MockHttpServletRequest request(String method, String uri, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setSession(session);
        return request;
    }

    private static void authenticate() {
        authenticateAs(1L, "admin");
    }

    private static void authenticateAs(Long userId, String username) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId("default");
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(user, authorities, "default"), null, authorities));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
