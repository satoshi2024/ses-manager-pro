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
import jakarta.servlet.DispatcherType;
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

    @BeforeEach
    void setUp() {
        properties.setBreakGlassLoginEnabled(true);
        properties.setBreakGlassUsernames(Set.of("BG-01", "BG-02"));
        when(auditMapper.insert(any(com.ses.entity.AuditLog.class))).thenReturn(1);
        when(incidentMapper.updateById(any(BreakGlassIncident.class))).thenReturn(1);
        when(userMapper.selectById(any())).thenAnswer(invocation -> admin(invocation.getArgument(0)));
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

    @Test
    void incident期限切れは既存sessionを即時失効する() {
        BreakGlassIncident incident = active();
        incident.setEnabledUntil(java.time.LocalDateTime.now(clock));
        when(incidentMapper.selectById(10L)).thenReturn(incident);
        MockHttpServletRequest request = boundRequest("dashboard.view");
        var authentication = new UsernamePasswordAuthenticationToken("BG-01", "n/a");

        assertEquals(BreakGlassService.BreakGlassDecision.REVOKE, service.validateBoundSession(request, authentication));
        verify(sessionService).revokeCurrent(request, authentication, "BREAK_GLASS_INCIDENT_EXPIRED");
    }

    @Test
    void 承認scope外のactionはDENY_SCOPEを返しsessionは維持する() {
        BreakGlassIncident incident = active();
        when(incidentMapper.selectById(10L)).thenReturn(incident);
        MockHttpServletRequest request = boundRequest("invoice.view");
        var authentication = new UsernamePasswordAuthenticationToken("BG-01", "n/a");

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(request, authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void dashboardだけ承認されたsessionは管理者MfaResetを実行できない() {
        BreakGlassIncident incident = active();
        when(incidentMapper.selectById(10L)).thenReturn(incident);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/security/mfa/123/reset");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        var authentication = new UsernamePasswordAuthenticationToken("BG-01", "n/a");

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(request, authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void 本人MfaStatusはincidentScope外でも認証継続のため許可する() {
        BreakGlassIncident incident = active();
        when(incidentMapper.selectById(10L)).thenReturn(incident);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/security/mfa/status");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        var authentication = new UsernamePasswordAuthenticationToken("BG-01", "n/a");

        assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(request, authentication));
    }

    @Test
    void MfaPageと同じUriでも誤ったmethodはscope免除しない() {
        BreakGlassIncident incident = active();
        when(incidentMapper.selectById(10L)).thenReturn(incident);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa/setup");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        var authentication = new UsernamePasswordAuthenticationToken("BG-01", "n/a");

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(request, authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void 安全methodの静的resourceはincident検証後にscope判定をskipする() {
        when(incidentMapper.selectById(10L)).thenReturn(active());
        var authentication = new UsernamePasswordAuthenticationToken("BG-01", "n/a");

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
    void 静的resourceと同じUriでも安全でないmethodはscope違反にする() {
        when(incidentMapper.selectById(10L)).thenReturn(active());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/css/common.css");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        var authentication = new UsernamePasswordAuthenticationToken("BG-01", "n/a");

        assertEquals(BreakGlassService.BreakGlassDecision.DENY_SCOPE, service.validateBoundSession(request, authentication));
        verifyNoInteractions(sessionService);
    }

    @Test
    void 内部errorDispatchだけscope判定をskipし直接error要求は拒否する() {
        when(incidentMapper.selectById(10L)).thenReturn(active());
        var authentication = new UsernamePasswordAuthenticationToken("BG-01", "n/a");
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
        when(incidentMapper.selectById(10L)).thenReturn(incident);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/security/mfa/123/reset");
        request.getSession(true).setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);

        assertEquals(BreakGlassService.BreakGlassDecision.ALLOW, service.validateBoundSession(request,
                new UsernamePasswordAuthenticationToken("BG-01", "n/a")));
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

    private SysUser admin(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole("管理者");
        user.setStatus(1);
        return user;
    }
}
