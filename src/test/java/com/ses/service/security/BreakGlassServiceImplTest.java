package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.BreakGlassIncident;
import com.ses.entity.SysUser;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.BreakGlassIncidentMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.impl.BreakGlassServiceImpl;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.config.LoginUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;

class BreakGlassServiceImplTest {

    private final BreakGlassIncidentMapper incidentMapper = mock(BreakGlassIncidentMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final AuditLogMapper auditMapper = mock(AuditLogMapper.class);
    private final PersistentSessionService sessionService = mock(PersistentSessionService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final OidcSecurityProperties properties = new OidcSecurityProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC);
    private BreakGlassServiceImpl service;
    private final java.util.List<SysUser> storedUsers = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        properties.setBreakGlassLoginEnabled(true);
        properties.setBreakGlassUsernames(Set.of("BG-01", "BG-02"));
        when(auditMapper.insert(any(com.ses.entity.AuditLog.class))).thenReturn(1);
        when(incidentMapper.updateById(any(BreakGlassIncident.class))).thenReturn(1);
        when(userMapper.selectById(any())).thenAnswer(invocation -> admin(invocation.getArgument(0)));
        when(userMapper.selectByIdAndTenant(any(), eq("default")))
                .thenAnswer(invocation -> admin(invocation.getArgument(0)));
        storedUsers.clear();
        storedUsers.add(namedAdmin(1L, "BG-01"));
        storedUsers.add(namedAdmin(2L, "BG-02"));
        // 名前ごとに行を解決する（全ての名前へ同じ口座を返すと、解決した行が別口座の誤りを検出できない）
        when(userMapper.selectByUsername(any())).thenAnswer(invocation -> storedUser(invocation.getArgument(0)));
        service = new BreakGlassServiceImpl(incidentMapper, userMapper, auditMapper,
                sessionService, notificationService, properties, clock);
    }

    @Test
    void activeIncidentがない設定usernameはlocalLoginできない() {
        when(incidentMapper.selectActive("default", java.time.LocalDateTime.now(clock))).thenReturn(null);
        assertFalse(service.isLoginAllowed("BG-01"));
    }

    @Test
    void 二人の別管理者が承認した時だけactiveになる() {
        BreakGlassIncident incident = pending();
        when(incidentMapper.selectByIdForUpdate("default", 10L)).thenReturn(incident);

        assertEquals("PENDING", service.approve(1L, 10L).getStatus());
        assertEquals("ACTIVE", service.approve(2L, 10L).getStatus());
        verify(notificationService, times(3)).publishToUser(any(), eq("BREAK_GLASS_ACTIVE"),
                eq("緊急アクセスが有効になりました"), any(), eq("/audit-log"), any(), eq("audit-log"));
        when(incidentMapper.selectActive("default", java.time.LocalDateTime.now(clock))).thenReturn(incident);
        assertTrue(service.isLoginAllowed("BG-01"));
    }

    @Test
    void 設定usernameの大文字小文字違いもDBの照合順序と同じくbreak_glassとして判定する() {
        // sys_user の照合順序は大文字小文字を区別しないため、表記違いも同じ行へ解決される（X3-2）
        when(incidentMapper.selectActive("default", java.time.LocalDateTime.now(clock))).thenReturn(active());

        assertTrue(service.isLoginAllowed("bg-01"));
        assertTrue(service.isLoginAllowed("Bg-02 "), "末尾の空白は DB でも同じ行");
        assertFalse(service.isLoginAllowed("bg-03"), "設定に無い名前は従来どおり拒否する");

        when(incidentMapper.selectActive("default", java.time.LocalDateTime.now(clock))).thenReturn(null);
        assertFalse(service.isLoginAllowed("bg-01"), "表記違いでもactiveなincidentが無ければ拒否する");
    }

    @Test
    void 先頭に空白がある別口座の行はbreak_glassとして扱わない() {
        // DOC-FINAL delta review P3-a: MySQL では先頭の空白に意味があり、" BG-01" は BG-01 と別の行になり得る。
        // 入力名は trim して設定と比べるが、解決した行の username は前後空白を除かずに比べる。
        storedUsers.add(namedAdmin(5L, " BG-01"));
        when(incidentMapper.selectActive("default", java.time.LocalDateTime.now(clock))).thenReturn(active());

        assertFalse(service.isLoginAllowed(" BG-01"), "先頭に空白がある別口座へ local login を許さない");
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertFalse(service.bindSession(request, " BG-01"), "先頭に空白がある別口座へ incident を束縛しない");
        assertTrue(request.getSession(false) == null);
        assertTrue(service.isLoginAllowed("BG-01"), "設定どおりの口座は従来どおり");
    }

    @Test
    void 入力名が設定と一致しても解決した行が名前の近い別口座ならbreak_glassとして扱わない() {
        // DOC-FINAL review P2-1: 照合順序が入力名を設定とは別の口座（ここでは全角の名前の口座）へ解決した場合
        when(incidentMapper.selectActive("default", java.time.LocalDateTime.now(clock))).thenReturn(active());
        when(userMapper.selectByUsername("BG-02")).thenReturn(namedAdmin(7L, "ＢＧ-02"));

        assertFalse(service.isLoginAllowed("BG-02"), "設定の口座ではない行へ local login を許さない");
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertFalse(service.bindSession(request, "BG-02"), "設定の口座ではない行へ incident を束縛しない");
        assertTrue(request.getSession(false) == null);
        assertTrue(service.isLoginAllowed("BG-01"), "設定どおりの口座は従来どおり");
    }

    @Test
    void 無点のıを含む別口座はbreak_glassのadminとして扱わない() {
        // DOC-FINAL review P2-1: equalsIgnoreCase は ı（U+0131）を I へ折り畳むが、DB では admin と別の行になる
        properties.setBreakGlassUsernames(Set.of("admin", "BG-02"));
        storedUsers.add(namedAdmin(8L, "admin"));
        storedUsers.add(namedAdmin(9L, "admın"));
        when(incidentMapper.selectActive("default", java.time.LocalDateTime.now(clock))).thenReturn(active());

        assertTrue(service.isLoginAllowed("ADMIN"), "ASCII の表記違いは同じ口座");
        assertFalse(service.isLoginAllowed("admın"));
        assertFalse(service.isLoginAllowed("ADMİN"), "点付きの İ も admin として扱わない（拒否側）");
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertTrue(service.bindSession(request, "admın"), "break-glass でない口座は従来どおり束縛の対象外");
        assertTrue(request.getSession(false) == null, "incident を束縛しない");
    }

    @Test
    void 同一管理者による二重承認は拒否する() {
        BreakGlassIncident incident = pending();
        incident.setApprovedBy1(1L);
        when(incidentMapper.selectByIdForUpdate("default", 10L)).thenReturn(incident);

        assertThrows(BusinessException.class, () -> service.approve(1L, 10L));
    }

    @Test
    void 申請者自身による承認は拒否する() {
        BreakGlassIncident incident = pending();
        when(incidentMapper.selectByIdForUpdate("default", 10L)).thenReturn(incident);

        assertThrows(BusinessException.class, () -> service.approve(99L, 10L));
    }

    @Test
    void 監査が保存できないincident起票は失敗する() {
        when(incidentMapper.insert(any(BreakGlassIncident.class))).thenReturn(1);
        when(auditMapper.insert(any(com.ses.entity.AuditLog.class))).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> service.create(1L, "IdP outage", true, 30, "INC-2026-001",
                        Set.of("dashboard.view")));
    }

    /** 機能の開放（FE-AC-012-02）: incident の許可 action に feature-enablement.* を入れた申請は 400 で、incident を作らない。 */
    @Test
    void 機能の開放のactionを許可するincidentは起票できない() {
        for (String action : java.util.List.of("feature-enablement.view", "feature-enablement.create")) {
            BusinessException rejected = assertThrows(BusinessException.class,
                    () -> service.create(1L, "IdP outage", true, 30, "INC-2026-002", Set.of("dashboard.view", action)));
            org.junit.jupiter.api.Assertions.assertEquals(400, rejected.getCode(), action);
            org.junit.jupiter.api.Assertions.assertEquals("error.breakGlass.invalidRequest", rejected.getMessageKey(), action);
        }
        org.mockito.Mockito.verify(incidentMapper, org.mockito.Mockito.never()).insert(any(BreakGlassIncident.class));
    }

    @Test
    void incident期限切れは既存sessionを即時失効する() {
        BreakGlassIncident incident = active();
        incident.setEnabledUntil(java.time.LocalDateTime.now(clock));
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(incident);
        MockHttpServletRequest request = boundRequest("dashboard.view");
        var authentication = breakGlassAuthentication();

        assertEquals(BreakGlassService.BreakGlassDecision.REVOKE, service.validateBoundSession(request, authentication));
        verify(sessionService).revokeCurrent(request, authentication, "BREAK_GLASS_INCIDENT_EXPIRED");
    }

    @Test
    void tenant欠落のbreakGlass主体は認証前scopeで拒否する() {
        BreakGlassIncident incident = active();
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(incident);
        SysUser user = admin(1L);
        user.setUsername("BG-01");
        user.setTenantId(null);
        var authorities = java.util.List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        var authentication = new UsernamePasswordAuthenticationToken(new LoginUser(user, authorities), "n/a", authorities);

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE,
                service.validateBoundSession(boundRequest("dashboard.view"), authentication));
        verifyNoInteractions(incidentMapper);
    }

    @Test
    void incidentと認証主体のtenantが不一致ならscope拒否する() {
        BreakGlassIncident incident = active();
        incident.setTenantId("tenant-a");
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(incident);

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE,
                service.validateBoundSession(boundRequest("dashboard.view"), breakGlassAuthentication()));
    }

    @Test
    void OIDC設定tenantとbreakGlass主体が不一致ならscope拒否する() {
        properties.setTenantId("tenant-a");
        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE,
                service.validateBoundSession(boundRequest("dashboard.view"), breakGlassAuthentication()));
        verifyNoInteractions(incidentMapper);
    }

    @Test
    void 承認scope外のactionはDENY_SCOPEを返しsessionは維持する() {
        BreakGlassIncident incident = active();
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(incident);
        MockHttpServletRequest request = boundRequest("invoice.view");
        var authentication = breakGlassAuthentication();

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(request, authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void dashboardだけ承認されたsessionは管理者MfaResetを実行できない() {
        BreakGlassIncident incident = active();
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(incident);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/security/mfa/123/reset");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        var authentication = breakGlassAuthentication();

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(request, authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void 本人MfaStatusはincidentScope外でも認証継続のため許可する() {
        BreakGlassIncident incident = active();
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(incident);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/security/mfa/status");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        var authentication = breakGlassAuthentication();

        assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(request, authentication));
    }

    @Test
    void MfaPageと同じUriでも誤ったmethodはscope免除しない() {
        BreakGlassIncident incident = active();
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(incident);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa/setup");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        var authentication = breakGlassAuthentication();

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(request, authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void 安全methodの静的resourceはincident検証後にscope判定をskipする() {
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(active());
        var authentication = breakGlassAuthentication();

        for (String uri : java.util.List.of(
                "/css/common.css", "/js/common.js", "/lib/bootstrap/bootstrap.min.js",
                "/img/logo.svg", "/favicon.ico")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
            assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(request, authentication), uri);
        }
        MockHttpServletRequest headRequest = new MockHttpServletRequest("HEAD", "/css/common.css");
        headRequest.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(headRequest, authentication));

        verifyNoInteractions(sessionService);
    }

    @Test
    void XForwardedPrefixや百分率エンコードで静的resourceや認証基盤とscope免除を誤判定しない() throws Exception {
        // FWD-SANITIZE P3-5: 清浄化が無い（Boot標準のForwardedHeaderFilter）場合でもアプリ内pathで判定する。
        // 修正前は /css・/api/security を前置すると、静的resource・認証基盤としてincident scope外のAPIを通していた。
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(active());
        var authentication = breakGlassAuthentication();

        for (String prefix : java.util.List.of("/css", "/lib", "/api/security", "/mfa", "/x")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/engineers");
            request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
            request.addHeader("X-Forwarded-Prefix", prefix);
            assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE,
                    service.validateBoundSession(viaVanillaForwardedHeaderFilter(request), authentication), prefix);
        }
        MockHttpServletRequest encoded = new MockHttpServletRequest("GET", "/%61pi/engineers");
        encoded.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE,
                service.validateBoundSession(encoded, authentication));
        verifyNoInteractions(sessionService);
    }

    private static jakarta.servlet.http.HttpServletRequest viaVanillaForwardedHeaderFilter(
            MockHttpServletRequest request) throws Exception {
        java.util.concurrent.atomic.AtomicReference<jakarta.servlet.http.HttpServletRequest> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        new org.springframework.web.filter.ForwardedHeaderFilter().doFilter(request,
                new org.springframework.mock.web.MockHttpServletResponse(),
                (req, res) -> seen.set((jakarta.servlet.http.HttpServletRequest) req));
        return seen.get();
    }

    @Test
    void 静的resourceと同じUriでも安全でないmethodはscope違反にする() {
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(active());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/css/common.css");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        var authentication = breakGlassAuthentication();

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(request, authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void 内部errorDispatchだけscope判定をskipし直接error要求は拒否する() {
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(active());
        var authentication = breakGlassAuthentication();
        MockHttpServletRequest dispatch = new MockHttpServletRequest("GET", "/error");
        dispatch.setDispatcherType(DispatcherType.ERROR);
        dispatch.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);

        assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(dispatch, authentication));

        MockHttpServletRequest direct = new MockHttpServletRequest("GET", "/error");
        direct.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(direct, authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void 管理者MfaResetは独立actionが明示承認された場合だけ許可する() {
        BreakGlassIncident incident = active();
        incident.setAllowedActions("mfa.reset");
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(incident);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/security/mfa/123/reset");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);

        assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(request,
                breakGlassAuthentication()));
    }

    // ---------------------------------------------------------------- 初回パスワード変更（D1 review P2-1）

    @Test
    void session変更要求のあるbreakGlassは変更画面と変更APIだけscope外でも許可する() {
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(active());
        var authentication = breakGlassAuthentication();

        for (String[] endpoint : new String[][]{{"GET", "/password/change"}, {"PUT", "/api/profile/password"}}) {
            MockHttpServletRequest request = passwordChangeRequest(endpoint[0], endpoint[1]);
            request.getSession().setAttribute(
                    com.ses.config.PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, Boolean.TRUE);
            assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(request, authentication),
                    endpoint[0] + " " + endpoint[1]);
        }
        verifyNoInteractions(sessionService);
    }

    @Test
    void 認証主体に変更要求のあるbreakGlassも変更経路を許可する() {
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(active());
        var authentication = breakGlassAuthentication();
        ((LoginUser) authentication.getPrincipal()).getSysUser().setPasswordChangeRequired(1);

        assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(
                passwordChangeRequest("PUT", "/api/profile/password"), authentication));
        assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(
                passwordChangeRequest("GET", "/password/change"), authentication));
    }

    @Test
    void 変更要求の無いbreakGlassは変更経路もscopeで判定しDENY_SCOPEにする() {
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(active());
        var authentication = breakGlassAuthentication();
        ((LoginUser) authentication.getPrincipal()).getSysUser().setPasswordChangeRequired(0);

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(
                passwordChangeRequest("GET", "/password/change"), authentication));
        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(
                passwordChangeRequest("PUT", "/api/profile/password"), authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void 変更要求があっても変更経路以外とmethod違いはscopeで判定する() {
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(active());
        var authentication = breakGlassAuthentication();

        for (String[] other : new String[][]{{"GET", "/invoice/list"}, {"POST", "/password/change"},
                {"GET", "/api/profile/password"}, {"PUT", "/api/users/1"}}) {
            MockHttpServletRequest request = passwordChangeRequest(other[0], other[1]);
            request.getSession().setAttribute(
                    com.ses.config.PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, Boolean.TRUE);
            assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(request, authentication),
                    other[0] + " " + other[1]);
        }
        verifyNoInteractions(sessionService);
    }

    @Test
    void 期限切れincidentでは変更要求があっても変更経路を許可しない() {
        BreakGlassIncident expired = active();
        expired.setEnabledUntil(java.time.LocalDateTime.now(clock).minusMinutes(1));
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(expired);
        MockHttpServletRequest request = passwordChangeRequest("GET", "/password/change");
        request.getSession().setAttribute(
                com.ses.config.PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, Boolean.TRUE);

        assertEquals(BreakGlassService.BreakGlassDecision.REVOKE,
                service.validateBoundSession(request, breakGlassAuthentication()));
    }

    /** NF07 W-5: privacy は既知resource（isKnownAction=true）になったが、緊急accessの対象にはしない。 */
    @Test
    void privacy接頭辞のactionを含むincident起票は400で拒否し保存しない() {
        for (Set<String> actions : java.util.List.of(Set.of("privacy.view"), Set.of("privacy.dsar.export"),
                Set.of("dashboard.view", "privacy.policy.view"))) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.create(1L, "IdP outage", true, 30, "INC-2026-001", actions));
            assertEquals(400, error.getCode());
        }
        org.mockito.Mockito.verify(incidentMapper, org.mockito.Mockito.never()).insert(any(BreakGlassIncident.class));
    }

    @Test
    void DBへ直接privacyを書いたincidentでもprivacyの要求はDENY_SCOPEにする() {
        BreakGlassIncident incident = active();
        incident.setAllowedActions("dashboard.view,privacy.policy.view,privacy.view");
        when(incidentMapper.selectByIdAndTenant("default", 10L)).thenReturn(incident);
        var authentication = breakGlassAuthentication();

        for (String[] request : new String[][]{{"GET", "/api/privacy/policies"}, {"GET", "/privacy/holds"}}) {
            MockHttpServletRequest privacyRequest = new MockHttpServletRequest(request[0], request[1]);
            privacyRequest.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
            assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE,
                    service.validateBoundSession(privacyRequest, authentication), request[1]);
        }
        assertEquals(BreakGlassService.BreakGlassDecision.ALLOW,
                service.validateBoundSession(boundRequest("dashboard.view"), authentication));
        verifyNoInteractions(sessionService);
    }

    private static MockHttpServletRequest passwordChangeRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        return request;
    }

    private BreakGlassIncident pending() {
        BreakGlassIncident incident = new BreakGlassIncident();
        incident.setId(10L);
        incident.setTenantId("default");
        incident.setStatus("PENDING");
        incident.setRequestedBy(99L);
        incident.setIdpOutageConfirmed(1);
        incident.setEnabledUntil(java.time.LocalDateTime.now(clock).plusMinutes(30));
        incident.setCorrelationId("INC-2026-001");
        return incident;
    }

    private BreakGlassIncident active() {
        BreakGlassIncident incident = pending();
        incident.setStatus("ACTIVE");
        incident.setApprovedBy1(1L);
        incident.setApprovedBy2(2L);
        incident.setEnabledFrom(java.time.LocalDateTime.now(clock).minusMinutes(1));
        incident.setAllowedActions("dashboard.view");
        return incident;
    }

    private MockHttpServletRequest boundRequest(String action) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        request.setAttribute(ActionPermissionResolver.REQUEST_ACTION_ATTRIBUTE, action);
        return request;
    }

    /**
     * {@code sys_user}（{@code utf8mb4_unicode_ci}、PAD SPACE）の username 解決を模す。ASCII の大文字小文字の違いと末尾の
     * 空白は同じ行になり、先頭の空白には意味がある（{@code " BG-01"} は {@code BG-01} と別の行）。無点の ı を含む名前も
     * 別の行になる（DOC-FINAL delta review P3-e）。
     */
    private SysUser storedUser(String username) {
        if (username == null) {
            return null;
        }
        String key = collationKey(username);
        return storedUsers.stream()
                .filter(row -> collationKey(row.getUsername()).equals(key))
                .findFirst()
                .orElse(null);
    }

    private static String collationKey(String value) {
        return value.replaceAll(" +$", "").toLowerCase(java.util.Locale.ROOT);
    }

    private SysUser namedAdmin(Long id, String username) {
        SysUser user = admin(id);
        user.setUsername(username);
        return user;
    }

    private SysUser admin(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId("default");
        return user;
    }

    private UsernamePasswordAuthenticationToken breakGlassAuthentication() {
        SysUser user = admin(1L);
        user.setUsername("BG-01");
        var authorities = java.util.List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        LoginUser principal = new LoginUser(user, authorities);
        return new UsernamePasswordAuthenticationToken(principal, "n/a", authorities);
    }
}
