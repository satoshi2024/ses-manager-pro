package com.ses.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.profile.PasswordChangeRequest;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 初回ログイン時のパスワード変更（配備決定-3）を実SecurityFilterChain・実controller・H2で検証する。
 *
 * <p>sessionの遮断属性は {@link LoginSuccessHandler} がlocal password loginで立てる（{@code LoginSuccessHandlerPasswordChangeTest}）。
 * ここでは属性を持つsessionで、変更前は画面・APIが遮断され、本人の変更で列と属性が消えて遮断が解けることを見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PasswordChangeEnforcementIntegrationTest {

    private static final String USERNAME = "pwchange-initial-admin";
    private static final String INITIAL_PASSWORD = "initialAdmin123";
    private static final String NEW_PASSWORD = "changedAdmin456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UsernamePasswordAuthenticationToken principal;
    private MockHttpSession session;

    @BeforeEach
    void seedUser() {
        // test profileはNoOpPasswordEncoderのため平文で投入する
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", USERNAME);
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, role, email, tenant_id, status, "
                        + "password_change_required) VALUES (?,?,?,?,?,'default',1,1)",
                USERNAME, INITIAL_PASSWORD, "初回変更テスト", "管理者", USERNAME + "@ses.test");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, USERNAME);
        principal = TenantTestSecurity.authentication(userId, USERNAME, "default", "管理者");
        session = new MockHttpSession();
        session.setAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, Boolean.TRUE);
    }

    @Test
    void 変更前は画面を変更画面へredirectしAPIを403にする() throws Exception {
        mockMvc.perform(get("/dashboard").session(session).with(authentication(principal)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password/change"));
        mockMvc.perform(get("/").session(session).with(authentication(principal)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password/change"));
        mockMvc.perform(get("/api/notifications").session(session).with(authentication(principal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("パスワードの変更が必要です"));
        // 変更API以外の更新系APIも通さない（GETの変更APIも対象外）
        mockMvc.perform(get("/api/profile/password").session(session).with(authentication(principal)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 変更画面は表示でき変更APIで遮断が解ける() throws Exception {
        mockMvc.perform(get("/password/change").session(session).with(authentication(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"password-change-form\"")))
                .andExpect(content().string(containsString("/js/modules/password-change.js")));

        PasswordChangeRequest request = new PasswordChangeRequest();
        request.setCurrentPassword(INITIAL_PASSWORD);
        request.setNewPassword(NEW_PASSWORD);
        mockMvc.perform(put("/api/profile/password").session(session).with(authentication(principal)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_change_required FROM sys_user WHERE username = ?", Integer.class, USERNAME))
                .as("本人の変更で初回変更要求が解除されること").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE username = ?", String.class, USERNAME))
                .isEqualTo(NEW_PASSWORD);
        assertThat(session.getAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE))
                .as("同じsessionの遮断も解除されること").isNull();

        mockMvc.perform(get("/api/notifications").session(session).with(authentication(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void 変更に失敗した場合は遮断を解かない() throws Exception {
        PasswordChangeRequest request = new PasswordChangeRequest();
        request.setCurrentPassword("wrong-current-1");
        request.setNewPassword(NEW_PASSWORD);
        mockMvc.perform(put("/api/profile/password").session(session).with(authentication(principal)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_change_required FROM sys_user WHERE username = ?", Integer.class, USERNAME))
                .isEqualTo(1);
        assertThat(session.getAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
        mockMvc.perform(get("/api/notifications").session(session).with(authentication(principal)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 遮断属性の無いsessionは従来どおり通す() throws Exception {
        mockMvc.perform(get("/api/notifications").session(new MockHttpSession()).with(authentication(principal)))
                .andExpect(status().isOk());
    }
}
