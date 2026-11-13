package com.ses.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.profile.PasswordChangeRequest;
import com.ses.entity.SysUser;
import com.ses.service.AuditLogService;
import com.ses.service.security.MfaAttemptService;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本番初回の期限付きbootstrap（{@link ProductionBootstrapMode}）を、実SecurityFilterChain・実form login・H2で検証する。
 *
 * <p>prod相当にするため通常local loginを無効にし（{@code local-login-enabled=false}）、bootstrap modeは
 * prod判定を明示したtest beanへ差し替える（test profileのままprod専用beanを起動しないため）。
 * MFAの暗号処理・永続session・監査の永続化は本testの対象外なのでmockにし、呼出しと順序だけを見る。
 */
@SpringBootTest(properties = {
        "app.security.oidc.local-login-enabled=false",
        "app.security.oidc.break-glass-usernames="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(ProductionBootstrapIntegrationTest.BootstrapModeConfiguration.class)
class ProductionBootstrapIntegrationTest {

    /** 2026-11-13T18:00:00+09:00。bootstrapの期限はその2時間後。 */
    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");
    private static final String EXPIRES_AT = "2026-11-13T20:00:00+09:00";
    private static final ProductionBootstrapModeTest.MutableClock CLOCK = new ProductionBootstrapModeTest.MutableClock(NOW);

    private static final String INITIAL_PASSWORD = "admin123";
    private static final String NEW_PASSWORD = "Bootstrap2026x";

    @TestConfiguration
    static class BootstrapModeConfiguration {

        @Bean
        @Primary
        ProductionBootstrapMode testProductionBootstrapMode() {
            ProductionBootstrapProperties properties = new ProductionBootstrapProperties();
            properties.setEnabled(true);
            properties.setExpiresAt(EXPIRES_AT);
            // prod判定を明示する（@PostConstructの期限検査もこのbeanで行われる）
            return new ProductionBootstrapMode(properties, true, CLOCK);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PersistentSessionService persistentSessionService;
    @MockBean
    private MfaService mfaService;
    @MockBean
    private MfaAttemptService mfaAttemptService;
    @MockBean
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        CLOCK.set(NOW);
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);
        when(mfaService.isRequired(any())).thenReturn(false);
        when(mfaService.isConfigured(any())).thenReturn(false);
        // V2の初期admin（test profileは平文encoder）を、配備jobが立てる初回変更要求の状態にする
        jdbcTemplate.update("UPDATE sys_user SET password = ?, password_change_required = 1, failed_count = 0, "
                + "locked_until = NULL, status = 1 WHERE username = 'admin'", INITIAL_PASSWORD);
    }

    @Test
    void bootstrap中もadmin以外のlocal_loginは拒否する() throws Exception {
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = 'bootstrap-operator'");
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, role, email, tenant_id, status) "
                        + "VALUES ('bootstrap-operator', ?, '運用管理者', '管理者', 'bootstrap-operator@ses.test', 'default', 1)",
                "Operator2026x");

        mockMvc.perform(formLogin("/login").user("bootstrap-operator").password("Operator2026x"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        verify(auditLogService).record(eq("bootstrap-operator"), eq("AUTH"), eq("/login"), eq(401),
                eq("LOGIN_FAILURE"), eq(false));
        verify(auditLogService, never()).recordRequired(eq("bootstrap-operator"), anyString(), anyString(), anyInt(),
                eq("BOOTSTRAP_LOGIN_SUCCESS"), anyBoolean());
    }

    @Test
    void adminは改密を先に強制されその後MFA登録を強制される() throws Exception {
        MockHttpSession session = loginAsBootstrapAdmin();
        assertThat(session.getAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        assertThat(session.getAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE))
                .as("改密 → MFAの順にするためMFA pendingは立てない").isNull();
        verify(auditLogService).recordRequired(eq("admin"), eq("AUTH"), eq("/login"), eq(200),
                eq("BOOTSTRAP_LOGIN_SUCCESS"), eq(true));

        // 1. 改密前: 画面は変更画面へ、MFA登録APIも含めAPIは403
        mockMvc.perform(get("/user/list").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PasswordChangeEnforcementFilter.CHANGE_PAGE));
        mockMvc.perform(post("/api/security/mfa/setup").session(session).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("パスワードの変更が必要です"));
        mockMvc.perform(get(PasswordChangeEnforcementFilter.CHANGE_PAGE).session(session))
                .andExpect(status().isOk());

        changePassword(session);
        verify(auditLogService).recordRequired(eq("admin"), eq("PUT"), eq("/api/profile/password"), eq(102),
                eq("BOOTSTRAP_WRITE_ATTEMPT"), eq(false));
        verify(auditLogService).record(eq("admin"), eq("PUT"), eq("/api/profile/password"), eq(200),
                eq("BOOTSTRAP_WRITE"), eq(true));

        // 2. 改密後: MFA完了までMFA登録画面へ誘導し、業務・bootstrap用APIも403
        mockMvc.perform(get("/").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mfa/setup"));
        mockMvc.perform(get("/api/users").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("MFA認証が必要です"));
        mockMvc.perform(get("/mfa/setup").session(session))
                .andExpect(status().isOk());

        // 3. 既存のMFA登録APIで登録する（break-glass usernameでなくてもbootstrap sessionなら使える）
        mockMvc.perform(post("/api/security/mfa/setup").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/security/mfa/enable").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(session.getAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        verify(mfaService).setup(any());
        verify(mfaService).enable(any(), eq("123456"));
        verify(auditLogService).recordRequired(eq("admin"), eq("AUTH"), eq("/api/security/mfa/enable"), eq(200),
                eq("BOOTSTRAP_MFA_ENABLED"), eq(true));

        // 4. 完了後: bootstrap用の入口だけ
        mockMvc.perform(get("/").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(BootstrapSessionFilter.LANDING_PAGE));
        mockMvc.perform(get("/user/list").session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/identity-providers").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void 完了したbootstrap_sessionでも入口以外のURLとAPIは403にする() throws Exception {
        MockHttpSession session = completedBootstrapSession();

        mockMvc.perform(get("/api/engineers").session(session))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-SES-Error-Code", "BOOTSTRAP_SCOPE_VIOLATION"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/engineers").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/system-configs").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notifications").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/dashboard").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/engineer/list").session(session))
                .andExpect(status().isForbidden());

        verify(auditLogService).record(eq("admin"), eq("GET"), eq("/api/engineers"), eq(403),
                eq("BOOTSTRAP_SCOPE_DENIED"), eq(false));
        verify(auditLogService).recordRequired(eq("admin"), eq("POST"), eq("/api/engineers"), eq(102),
                eq("BOOTSTRAP_WRITE_ATTEMPT"), eq(false));
        verify(auditLogService).record(eq("admin"), eq("POST"), eq("/api/engineers"), eq(403),
                eq("BOOTSTRAP_WRITE"), eq(false));
    }

    @Test
    void 運用中に期限を過ぎるとbootstrap_sessionと新しいbootstrap_loginを拒否する() throws Exception {
        MockHttpSession session = completedBootstrapSession();
        mockMvc.perform(get("/api/users").session(session)).andExpect(status().isOk());

        CLOCK.set(Instant.parse("2026-11-13T11:00:00Z"));

        mockMvc.perform(get("/api/users").session(session))
                .andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).as("期限切れのbootstrap sessionは破棄する").isTrue();
        verify(auditLogService).record(eq("admin"), eq("GET"), eq("/api/users"), eq(401),
                eq("BOOTSTRAP_SESSION_EXPIRED"), eq(false));
        verify(persistentSessionService).revokeCurrent(any(), any(), eq("BOOTSTRAP_EXPIRED"));

        mockMvc.perform(formLogin("/login").user("admin").password(NEW_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
        verify(auditLogService).record(eq("admin"), eq("AUTH"), eq("/login"), eq(401),
                eq("BOOTSTRAP_LOGIN_EXPIRED"), eq(false));
    }

    @Test
    void bootstrap中はOIDCの管理者もMFAを登録でき一般sessionは入口制限を受けない() throws Exception {
        Long adminId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        SysUser user = new SysUser();
        user.setId(adminId);
        user.setUsername("admin");
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId("default");
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        OidcIdToken idToken = OidcIdToken.withTokenValue("bootstrap-test-id-token")
                .subject("idp-subject-1").issuer("https://idp.invalid/tenant")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        OidcLoginUser principal = new OidcLoginUser(user, new DefaultOidcUser(authorities, idToken), authorities);
        OAuth2AuthenticationToken oidc = new OAuth2AuthenticationToken(principal, authorities, "enterprise");

        mockMvc.perform(post("/api/security/mfa/setup").session(new MockHttpSession())
                        .with(authentication(oidc)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // bootstrap sessionではないため入口制限は掛からない（bootstrap sessionでは403になる通知APIも通る）
        mockMvc.perform(get("/api/notifications").session(new MockHttpSession()).with(authentication(oidc)))
                .andExpect(header().doesNotExist("X-SES-Error-Code"));

        CLOCK.set(Instant.parse("2026-11-13T11:00:00Z"));
        mockMvc.perform(post("/api/security/mfa/setup").session(new MockHttpSession())
                        .with(authentication(oidc)).with(csrf()))
                .andExpect(jsonPath("$.code").value(403));
    }

    // ---------------------------------------------------------------- helper

    private MockHttpSession loginAsBootstrapAdmin() throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/login").user("admin").password(INITIAL_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PasswordChangeEnforcementFilter.CHANGE_PAGE))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private void changePassword(MockHttpSession session) throws Exception {
        PasswordChangeRequest request = new PasswordChangeRequest();
        request.setCurrentPassword(INITIAL_PASSWORD);
        request.setNewPassword(NEW_PASSWORD);
        mockMvc.perform(put("/api/profile/password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(session.getAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE)).isNull();
    }

    private MockHttpSession completedBootstrapSession() throws Exception {
        MockHttpSession session = loginAsBootstrapAdmin();
        changePassword(session);
        mockMvc.perform(post("/api/security/mfa/enable").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk());
        assertThat(session.getAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        return session;
    }
}
