package com.ses.controller.api;

import com.ses.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
@TestPropertySource(properties = {"ai.enabled=true", "ai.provider=mock"})
class AiChatApiKeyRejectionTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void 旧apiKeyフィールド付きリクエストはcode400で拒否される() throws Exception {
        // ApiResult 規約に従い HTTP は 200、ボディの code が 400。クライアント供給キーは使用・エコーしない。
        mockMvc.perform(post("/api/ai/chat").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"client-supplied-secret\",\"prompt\":\"こんにちは\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("client-supplied-secret"))));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void APIキー無しの正常リクエストはサーバー設定で成功する() throws Exception {
        mockMvc.perform(post("/api/ai/chat").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"こんにちは\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
