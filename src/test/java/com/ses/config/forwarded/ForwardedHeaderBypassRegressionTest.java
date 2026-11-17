package com.ses.config.forwarded;

import com.ses.common.util.ClientIpResolver;
import com.ses.common.util.SecurityInfrastructureUtils;
import com.ses.config.ApiAuditFilter;
import com.ses.config.MenuPermissionFilter;
import com.ses.config.MfaEnforcementFilter;
import com.ses.config.PasswordChangeEnforcementFilter;
import com.ses.config.PortalSecurityProperties;
import com.ses.config.portal.PortalRateLimitFilter;
import com.ses.entity.Menu;
import com.ses.service.AuditLogService;
import com.ses.service.MenuCacheService;
import com.ses.service.portal.impl.PortalRateLimiterImpl;
import com.ses.service.security.AuthorizationService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FWD-SANITIZE PRE-P1-1 / PRE-P2-1 の再現テストを、修正後の拒否・記録のassertへ置き換えたもの。
 *
 * <p>修正前（Boot標準の {@link ForwardedHeaderFilter} ＋ {@code getRequestURI()} 判定）は、clientが
 * {@code X-Forwarded-Prefix: /x} を送るだけで次が成立していた（scratchpadのharnessで確認済み）。
 * <ul>
 *   <li>営業が m_menu で禁止された {@code /api/compliance/**} へ到達（200）</li>
 *   <li>{@code POST /api/**} が t_audit_log に記録されない</li>
 *   <li>Portal login の IP rate limit が11回目でも429にならない</li>
 *   <li>XFF最左を入れ替えるとPortal loginのIP rate limitが毎回別keyになる</li>
 *   <li>MFA未完了sessionが {@code X-Forwarded-Prefix: /mfa}・{@code /css} でAPIへ到達</li>
 * </ul>
 * 各テストは前段filterを2種類で実行する。{@code sanitizing} は本番構成（清浄化filter）、{@code vanilla} は
 * 清浄化が無い場合（Boot標準filter）でも自前filterがアプリ内pathで判定して拒否できること（多層防御）を確かめる。
 */
class ForwardedHeaderBypassRegressionTest {

    private static final String PEER = "10.0.0.5";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Filter front(String mode) {
        return "vanilla".equals(mode)
                ? new ForwardedHeaderFilter()
                : new ForwardedHeaderSanitizingFilter(new ClientIpResolver("10.0.0.0/24"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user-" + role, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static MockHttpServletRequest request(String method, String uri, String prefix) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(PEER);
        if (prefix != null) {
            request.addHeader("X-Forwarded-Prefix", prefix);
        }
        return request;
    }

    /** filter列を通し、servletへ到達したrequest（未到達ならnull）とstatusを返す。 */
    private static Result run(MockHttpServletRequest request, Filter... filters) throws Exception {
        AtomicReference<HttpServletRequest> reached = new AtomicReference<>();
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                reached.set(req);
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(servlet, filters).doFilter(request, response);
        return new Result(response.getStatus(), reached.get());
    }

    private record Result(int status, HttpServletRequest reached) {
    }

    private static MenuPermissionFilter menuPermissionFilter(String allowedMenu, AuditLogService auditLogService) {
        Menu compliance = new Menu();
        compliance.setMenuKey("compliance");
        compliance.setPathPrefix("/compliance");
        compliance.setApiPrefix("/api/compliance");
        MenuCacheService menuCache = mock(MenuCacheService.class);
        when(menuCache.getAllMenus()).thenReturn(List.of(compliance));
        when(menuCache.getMenuKeysByRole(anyString())).thenReturn(List.of(allowedMenu));
        // baseline + deny: 営業の compliance.view はaction層では許可され、m_menuだけが遮断している
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        return new MenuPermissionFilter(provider(menuCache), provider(authorizationService), provider(auditLogService));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void 営業はXForwardedPrefix付きでもmenuで禁止されたcomplianceAPIへ到達できない(String mode) throws Exception {
        authenticate("営業");
        AuditLogService auditLogService = mock(AuditLogService.class);

        Result result = run(request("GET", "/api/compliance/findings", "/x"),
                front(mode), menuPermissionFilter("engineer", auditLogService));

        assertEquals(403, result.status());
        assertNull(result.reached());
        verify(auditLogService).record(any(), eq("GET"), eq("/api/compliance/findings"), eq(403),
                eq("PERMISSION_DENIED"), eq(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void menu許可のあるroleはXForwardedPrefix付きでも従来どおり到達できる(String mode) throws Exception {
        authenticate("マネージャー");

        Result result = run(request("GET", "/api/compliance/findings", "/x"),
                front(mode), menuPermissionFilter("compliance", mock(AuditLogService.class)));

        assertEquals(200, result.status());
        assertEquals("/api/compliance/findings",
                com.ses.common.util.RequestPaths.pathWithinApplication(result.reached()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void XForwardedPrefix付きの更新系APIも監査に記録される(String mode) throws Exception {
        authenticate("営業");
        AuditLogService auditLogService = mock(AuditLogService.class);

        Result result = run(request("POST", "/api/engineers", "/x"),
                front(mode), new ApiAuditFilter(provider(auditLogService)));

        assertEquals(200, result.status());
        verify(auditLogService).record(any(), eq("POST"), eq("/api/engineers"), eq(200),
                eq("ses-manager"), eq(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void XForwardedPrefix付きでもPortalLoginは11回目で429になる(String mode) throws Exception {
        PortalRateLimitFilter rateLimit = new PortalRateLimitFilter(new PortalRateLimiterImpl(),
                new PortalSecurityProperties(), new ClientIpResolver("10.0.0.0/24"));
        List<Integer> statuses = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            statuses.add(run(request("POST", "/api/portal/auth/login", "/x"), front(mode), rateLimit).status());
        }

        assertFalse(statuses.subList(0, 10).contains(429), statuses.toString());
        assertEquals(429, statuses.get(10));
    }

    @Test
    void XFF左端を毎回変えてもPortalLoginは11回目で429になる() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/24");
        PortalRateLimitFilter rateLimit = new PortalRateLimitFilter(new PortalRateLimiterImpl(),
                new PortalSecurityProperties(), resolver);
        List<Integer> statuses = new ArrayList<>();
        List<String> ips = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            MockHttpServletRequest request = request("POST", "/api/portal/auth/login", null);
            // ALBのappend mode: 左側はclientが書いた値、右端はALBが見た送信元
            request.addHeader("X-Forwarded-For", "203.0.113." + i + ", 198.51.100.77");
            Filter capture = (req, res, chain) -> {
                ips.add(resolver.resolve((HttpServletRequest) req));
                chain.doFilter(req, res);
            };
            statuses.add(run(request, front("sanitizing"), capture, rateLimit).status());
        }

        assertEquals(List.of("198.51.100.77"), ips.stream().distinct().toList());
        assertEquals(429, statuses.get(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void MFA未完了sessionはXForwardedPrefixにmfaやcssを前置してもAPIへ到達できない(String mode) throws Exception {
        for (String prefix : List.of("/mfa", "/css", "/lib", "/x")) {
            authenticate("管理者");
            MockHttpServletRequest request = request("GET", "/api/engineers", prefix);
            MockHttpSession session = new MockHttpSession();
            session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);
            request.setSession(session);

            Result result = run(request, front(mode), new MfaEnforcementFilter());

            assertEquals(403, result.status(), prefix);
            assertNull(result.reached(), prefix);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void 初回パスワード変更前はXForwardedPrefixにcssを前置してもAPIへ到達できない(String mode) throws Exception {
        authenticate("営業");
        MockHttpServletRequest request = request("GET", "/api/engineers", "/css");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, Boolean.TRUE);
        request.setSession(session);

        Result result = run(request, front(mode), new PasswordChangeEnforcementFilter());

        assertEquals(403, result.status());
        assertNull(result.reached());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void XForwardedPrefixで静的資源やERRORディスパッチと誤判定しない(String mode) throws Exception {
        AtomicReference<Boolean> staticResource = new AtomicReference<>();
        AtomicReference<Boolean> errorDispatch = new AtomicReference<>();
        Filter probe = (req, res, chain) -> {
            staticResource.set(SecurityInfrastructureUtils.isStaticResource((HttpServletRequest) req));
            errorDispatch.set(SecurityInfrastructureUtils.isErrorDispatch((HttpServletRequest) req));
            chain.doFilter(req, res);
        };

        run(request("GET", "/api/engineers", "/css"), front(mode), probe);
        assertFalse(staticResource.get());

        MockHttpServletRequest error = request("GET", "/api/engineers", "/error");
        error.setDispatcherType(DispatcherType.ERROR);
        run(error, front(mode), probe);
        assertFalse(errorDispatch.get());
    }

    @Test
    void 素のForwardedHeaderFilterはprefixとXFF最左で判定材料を書き換える() throws Exception {
        // 再現の前提（修正前の挙動）。清浄化filterが無いとgetRequestURI()とgetRemoteAddr()がclientの値になる。
        MockHttpServletRequest request = request("GET", "/api/compliance/findings", "/x");
        request.addHeader("X-Forwarded-For", "203.0.113.1, 198.51.100.77");

        Result result = run(request, new ForwardedHeaderFilter());

        assertEquals("/x/api/compliance/findings", result.reached().getRequestURI());
        assertEquals("203.0.113.1", result.reached().getRemoteAddr());
    }

    @Test
    void 清浄化filterの後ではrequestURIもremoteAddrも書き換わらない() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/compliance/findings", "/x");
        request.addHeader("X-Forwarded-For", "203.0.113.1, 198.51.100.77");

        Result result = run(request, new ForwardedHeaderSanitizingFilter(new ClientIpResolver("")));

        assertEquals("/api/compliance/findings", result.reached().getRequestURI());
        assertEquals(PEER, result.reached().getRemoteAddr());
        assertEquals(PEER, new ClientIpResolver("").resolve(result.reached()));
    }

    // ---------------------------------------------------------------- P3-5: 他のallow-list・判定filter

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void 永続session検証はXForwardedPrefixにcssを前置しても静的資源扱いで省略しない(String mode) throws Exception {
        authenticate("営業");
        com.ses.service.security.PersistentSessionService sessions =
                mock(com.ses.service.security.PersistentSessionService.class);
        when(sessions.validateAndTouch(any(), any())).thenReturn(false);
        com.ses.config.PersistentSessionFilter filter = new com.ses.config.PersistentSessionFilter(
                provider(sessions), provider(null), provider(mock(AuditLogService.class)));

        Result result = run(request("GET", "/api/engineers", "/css"), front(mode), filter);

        assertEquals(401, result.status());
        assertNull(result.reached());
        verify(sessions).validateAndTouch(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void 動的APIはXForwardedPrefixにcssを前置してもno_storeになる(String mode) throws Exception {
        MockHttpServletRequest request = request("GET", "/api/engineers", "/css");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                // 応答本文は不要。filterが付けたheaderだけを確かめる。
            }
        }, front(mode), new com.ses.config.PwaNoStoreFilter()).doFilter(request, response);

        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sanitizing", "vanilla"})
    void 規約同意待ちのPortal利用者はXForwardedPrefixにportalのcssを前置してもAPIへ到達できない(String mode)
            throws Exception {
        com.ses.service.portal.PortalSessionService sessions = mock(com.ses.service.portal.PortalSessionService.class);
        when(sessions.resolve(any())).thenReturn(com.ses.portal.PortalLoginUser.builder()
                .portalUserId(1L).email("portal@example.invalid").termsPending(true).build());
        com.ses.config.portal.PortalSessionFilter filter = new com.ses.config.portal.PortalSessionFilter(sessions,
                new PortalSecurityProperties(), mock(com.ses.service.accounting.AccountingTimezoneResolver.class));

        Result result = run(request("GET", "/api/portal/customer/contracts", "/portal/css"), front(mode), filter);

        assertEquals(403, result.status());
        assertNull(result.reached());
    }

    @Test
    void IPv6のPortal_loginは同じ64bit_network内でaddressを替えても11回目で429になる() throws Exception {
        PortalRateLimitFilter rateLimit = new PortalRateLimitFilter(new PortalRateLimiterImpl(),
                new PortalSecurityProperties(), new ClientIpResolver("10.0.0.0/24"));
        List<Integer> statuses = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            MockHttpServletRequest request = request("POST", "/api/portal/auth/login", null);
            request.addHeader("X-Forwarded-For", "2001:db8:1:2::" + Integer.toHexString(i));
            statuses.add(run(request, front("sanitizing"), rateLimit).status());
        }
        MockHttpServletRequest otherNetwork = request("POST", "/api/portal/auth/login", null);
        otherNetwork.addHeader("X-Forwarded-For", "2001:db8:1:3::1");

        assertEquals(429, statuses.get(10), statuses.toString());
        assertEquals(200, run(otherNetwork, front("sanitizing"), rateLimit).status());
    }

    @Test
    void 監査には制御文字と書式文字を百分率表記へ戻したpathを残す() throws Exception {
        // P3-4: 復号後のpathをそのまま書くとESCやbidi制御で監査画面・logの表示を偽装できる
        authenticate("営業");
        AuditLogService auditLogService = mock(AuditLogService.class);
        MockHttpServletRequest request = request("POST", "/api/engineers/%1B%E2%80%AE1", null);
        request.setCharacterEncoding("UTF-8");

        run(request, front("sanitizing"), new ApiAuditFilter(provider(auditLogService)));

        verify(auditLogService).record(any(), eq("POST"), eq("/api/engineers/%1B%E2%80%AE1"), eq(200),
                eq("ses-manager"), eq(true));
    }
}
