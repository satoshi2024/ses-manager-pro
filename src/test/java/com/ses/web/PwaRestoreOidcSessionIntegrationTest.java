package com.ses.web;

import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import com.ses.entity.SysUser;
import com.ses.service.AuditLogService;
import com.ses.service.pwa.PwaRestoreAuthorizationService;
import com.ses.service.security.PersistentSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 実SecurityFilterChain（tenant・menu・CSRF・監査filter）を通したOIDC sessionの下書き復元拒否（NF04 P1-N4-1 方案A）。
 *
 * <p>OIDCの実IdP loginは再現しないため、{@code OidcLoginUserService} が作るのと同じ
 * {@link OAuth2AuthenticationToken} + {@link OidcLoginUser} をsessionの認証主体として渡す。
 * 永続session・監査の永続化は本testの対象外なのでmockにする（{@code ProductionBootstrapIntegrationTest} と同じ）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PwaRestoreOidcSessionIntegrationTest {
    /** test profileの {@code app.security.oidc.tenant-id}（InternalTenantContextFilterが照合する）。 */
    private static final String TENANT = "default";
    private static final String UNSUPPORTED_MESSAGE =
            "現在のログイン方式（シングルサインオン）では、ロック中の下書きを復元できません。必要な内容は再入力してください。";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private PersistentSessionService persistentSessionService;
    @MockBean private AuditLogService auditLogService;

    private Long userId;
    private String username;

    @BeforeEach
    void seedLinkedEngineer() {
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);
        username = "pwaoidc" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password, role, status, failed_count, deleted_flag) "
                + "VALUES (?, ?, ?, '要員', 1, 0, 0)", TENANT, username, "local-password");
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbc.update("INSERT INTO t_engineer (tenant_id, full_name, employment_type, status, deleted_flag) "
                + "VALUES (?, ?, '正社員', '稼動中', 0)", TENANT, "PWA OIDC " + userId);
        Long engineerId = jdbc.queryForObject("SELECT id FROM t_engineer WHERE tenant_id = ? AND full_name = ?",
                Long.class, TENANT, "PWA OIDC " + userId);
        jdbc.update("INSERT INTO t_engineer_account_link (tenant_id, engineer_id, sys_user_id, linked_at) "
                + "VALUES (?, ?, ?, ?)", TENANT, engineerId, userId, LocalDateTime.now().minusDays(1));
    }

    @Test
    void oidcSessionはsessionContextで復元不可を返し復元APIを専用codeで拒否して失敗回数を数えない() throws Exception {
        OAuth2AuthenticationToken oidc = oidcAuthentication();
        MockHttpSession session = new MockHttpSession();

        MvcResult context = mockMvc.perform(get("/api/my/session-context").session(session)
                        .with(authentication(oidc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.restoreAvailable").value(false))
                .andReturn();
        String scope = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(context.getResponse().getContentAsString()).path("data").path("userScope").asText();
        assertThat(scope).isNotBlank();

        // lock閾値（5回）を超える回数、IdPのパスワード・ローカルパスワードのどちらを送っても照合しない。
        for (int attempt = 0; attempt < 6; attempt++) {
            String password = attempt == 5 ? "local-password" : "idp-password-" + attempt;
            MvcResult rejected = mockMvc.perform(post("/api/my/pwa/restore/reauthenticate").session(session)
                            .with(authentication(oidc)).with(csrf())
                            .header("Accept-Language", "ja")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"" + password + "\",\"documentNonce\":\"AAAAAAAAAAAAAAAAAAAAAA\","
                                    + "\"userScope\":\"" + scope + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("X-SES-Error-Code", "PWA_RESTORE_REAUTH_UNSUPPORTED"))
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.data.type").value("PWA_RESTORE_REAUTH_UNSUPPORTED"))
                    .andExpect(jsonPath("$.message").value(UNSUPPORTED_MESSAGE))
                    .andReturn();
            assertThat(rejected.getResponse().getContentAsString()).doesNotContain(password, "proof");
        }
        mockMvc.perform(post("/api/my/pwa/restore/confirm").session(session)
                        .with(authentication(oidc)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proof\":\"" + "p".repeat(43) + "\",\"documentNonce\":\"AAAAAAAAAAAAAAAAAAAAAA\","
                                + "\"userScope\":\"" + scope + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.type").value("PWA_RESTORE_REAUTH_UNSUPPORTED"));

        assertThat(jdbc.queryForObject("SELECT failed_count FROM sys_user WHERE id = ?", Integer.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until FROM sys_user WHERE id = ?", LocalDateTime.class, userId))
                .isNull();
        assertThat(session.getAttribute(PwaRestoreAuthorizationService.SESSION_STATE_ATTRIBUTE)).isNull();
    }

    @Test
    void ローカルパスワードsessionはsessionContextで復元可を返す() throws Exception {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_要員"));
        LoginUser principal = new LoginUser(user(), authorities, TENANT);
        mockMvc.perform(get("/api/my/session-context").session(new MockHttpSession())
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                                principal, "", authorities))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restoreAvailable").value(true));
    }

    private OAuth2AuthenticationToken oidcAuthentication() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_要員"));
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("pwa-oidc-it-id-token")
                .subject("idp-" + userId).issuer("https://idp.invalid/" + TENANT)
                .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        return new OAuth2AuthenticationToken(
                new OidcLoginUser(user(), new DefaultOidcUser(authorities, idToken), authorities),
                authorities, "enterprise");
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setTenantId(TENANT);
        user.setRole("要員");
        user.setStatus(1);
        return user;
    }
}
