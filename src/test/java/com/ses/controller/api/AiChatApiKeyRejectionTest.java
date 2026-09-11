package com.ses.controller.api;

import com.ses.BaseIntegrationTest;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2-3 (ACC-SEC-P1-004): AIチャットAPIがクライアント供給のAPIキーを受け付けないことの検証。
 *
 * <p>旧 {@code apiKey} フィールドが送られてきた場合は 400 で拒否し、サイレントに無視・使用しない。
 * APIキー無しの正常リクエストはサーバー側設定(モックプロバイダー)で成功する。
 */
@TestPropertySource(properties = {
        "ai.enabled=true",
        "ai.provider=mock",
        "ai.external-send-enabled=false",
        "app.security.oidc.tenant-id="
})
class AiChatApiKeyRejectionTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 旧apiKeyフィールド付きリクエストはcode400で拒否される() throws Exception {
        // ApiResult 規約に従い HTTP は 200、ボディの code が 400。クライアント供給キーは使用・エコーしない。
        mockMvc.perform(post("/api/ai/chat")
                        .with(tenantUser("default"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"client-supplied-secret\",\"prompt\":\"こんにちは\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("client-supplied-secret"))));
    }

    @Test
    void APIキー無しの正常リクエストはサーバー設定で成功する() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .with(tenantUser("default"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"こんにちは\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private RequestPostProcessor tenantUser(String tenantId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("admin123");
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId(tenantId);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
    }
}
