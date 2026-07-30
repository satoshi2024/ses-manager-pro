package com.ses.config;

import com.ses.entity.BreakGlassIncident;
import com.ses.entity.SysUser;
import com.ses.mapper.BreakGlassIncidentMapper;
import com.ses.service.AuditLogService;
import com.ses.service.security.BreakGlassService;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T017: 実SecurityFilterChainでbreak-glass画面が発行するrequest列を固定する。 */
@SpringBootTest(properties = "app.security.oidc.break-glass-usernames=BG-01")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BreakGlassFilterChainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BreakGlassIncidentMapper incidentMapper;
    @MockBean
    private PersistentSessionService persistentSessionService;
    @MockBean
    private MfaService mfaService;
    @MockBean
    private AuditLogService auditLogService;

    @Test
    void mfaPageからdashboardまで静的resourceを許可し通知pollingを生成しない() throws Exception {
        when(incidentMapper.selectById(10L)).thenReturn(activeIncident());
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);
        when(mfaService.isRequired(any())).thenReturn(true);
        when(mfaService.isConfigured(1L)).thenReturn(true);
        MockHttpSession session = boundSession();
        session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);

        mockMvc.perform(get("/mfa/challenge").session(session).with(breakGlassAuthentication()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/common.js")))
                .andExpect(content().string(not(containsString("id=\"notification-list\""))));

        mockMvc.perform(get("/css/common.css").session(session).with(breakGlassAuthentication()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/security/mfa/status").session(session).with(breakGlassAuthentication()))
                .andExpect(status().isOk());

        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        mockMvc.perform(get("/dashboard").session(session).with(breakGlassAuthentication()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"notification-list\""))));

        verify(persistentSessionService, never()).revokeCurrent(any(), any(), any());
    }

    @Test
    void dashboardScopeで通知Apiを直接要求するとsessionを失効する() throws Exception {
        when(incidentMapper.selectById(10L)).thenReturn(activeIncident());
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/notifications")
                        .session(boundSession()).with(breakGlassAuthentication()))
                .andExpect(status().isUnauthorized());

        verify(persistentSessionService).revokeCurrent(any(), any(), eq("BREAK_GLASS_SCOPE_VIOLATION"));
    }

    @Test
    void 未知Apiと未知pageはfullChainでもfailClosedにする() throws Exception {
        when(incidentMapper.selectById(10L)).thenReturn(activeIncident());
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/future-sensitive")
                        .session(boundSession()).with(breakGlassAuthentication()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/future-sensitive")
                        .session(boundSession()).with(breakGlassAuthentication()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login?sessionExpired"));
    }

    private MockHttpSession boundSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        return session;
    }

    private RequestPostProcessor breakGlassAuthentication() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("BG-01");
        user.setRole("管理者");
        user.setStatus(1);
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        LoginUser principal = new LoginUser(user, authorities);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private BreakGlassIncident activeIncident() {
        BreakGlassIncident incident = new BreakGlassIncident();
        incident.setId(10L);
        incident.setTenantId("test-tenant");
        incident.setStatus("ACTIVE");
        incident.setIdpOutageConfirmed(1);
        incident.setApprovedBy1(11L);
        incident.setApprovedBy2(12L);
        incident.setEnabledFrom(LocalDateTime.now().minusMinutes(1));
        incident.setEnabledUntil(LocalDateTime.now().plusMinutes(30));
        incident.setAllowedActions("dashboard.view");
        return incident;
    }
}
