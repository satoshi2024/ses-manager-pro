package com.ses.controller.api;

import com.ses.dto.pwa.PwaRestorePermitResponse;
import com.ses.dto.pwa.PwaRestoreProofResponse;
import com.ses.service.pwa.PwaRestoreAuthorizationService;
import com.ses.service.pwa.PwaRestoreReauthUnsupportedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 再認証APIのCSRF境界と監査対象secret非露出を確認する。 */
@WebMvcTest(MyPwaRestoreApiController.class)
class MyPwaRestoreApiControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private PwaRestoreAuthorizationService authorizationService;

    @Test
    @WithMockUser(username = "user-a", roles = "要員")
    void reauthenticateはCSRFなしを拒否しcontrollerへ渡さない() throws Exception {
        mockMvc.perform(post("/api/my/pwa/restore/reauthenticate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"never-log-this","documentNonce":"AAAAAAAAAAAAAAAAAAAAAA","userScope":"scope"}
                                """))
                .andExpect(status().isForbidden());
        verify(authorizationService, never()).reauthenticate(any(), any(), any());
    }

    @Test
    @WithMockUser(username = "user-a", roles = "要員")
    void reauthenticateはCSRF後だけproofを返す() throws Exception {
        when(authorizationService.reauthenticate(any(), any(), any()))
                .thenReturn(new PwaRestoreProofResponse("p".repeat(43), Instant.parse("2026-10-02T01:02:00Z")));
        MvcResult result = mockMvc.perform(post("/api/my/pwa/restore/reauthenticate").with(csrf())
                        .session(new MockHttpSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"never-log-this","documentNonce":"AAAAAAAAAAAAAAAAAAAAAA","userScope":"scope"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proof").value("p".repeat(43)))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("never-log-this");
    }

    @Test
    @WithMockUser(username = "user-a", roles = "要員")
    void confirmはCSRF付きの明示POSTだけをserviceへ渡す() throws Exception {
        when(authorizationService.confirm(any(), any(), any())).thenReturn(new PwaRestorePermitResponse(
                "nonce", "scope", Instant.parse("2026-10-02T01:00:00Z"),
                Instant.parse("2026-10-02T01:15:00Z")));
        mockMvc.perform(post("/api/my/pwa/restore/confirm").with(csrf())
                        .session(new MockHttpSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"proof":"p","documentNonce":"AAAAAAAAAAAAAAAAAAAAAA","userScope":"scope"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userScope").value("scope"))
                .andExpect(jsonPath("$.data.permitExpiresAt").exists());
        verify(authorizationService).confirm(any(), any(), any());
    }

    @Test
    @WithMockUser(username = "user-a", roles = "要員")
    void パスワード再認証に対応しないsessionは専用codeとmessageKeyの403で入力値を返さない() throws Exception {
        when(authorizationService.reauthenticate(any(), any(), any()))
                .thenThrow(new PwaRestoreReauthUnsupportedException());
        MvcResult result = mockMvc.perform(post("/api/my/pwa/restore/reauthenticate").with(csrf())
                        .session(new MockHttpSession()).contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "ja")
                        .content("""
                                {"password":"never-log-this","documentNonce":"AAAAAAAAAAAAAAAAAAAAAA","userScope":"scope-secret"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-SES-Error-Code", "PWA_RESTORE_REAUTH_UNSUPPORTED"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data.type").value("PWA_RESTORE_REAUTH_UNSUPPORTED"))
                .andExpect(jsonPath("$.message").value(
                        "現在のログイン方式（シングルサインオン）では、ロック中の下書きを復元できません。必要な内容は再入力してください。"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("never-log-this", "scope-secret", "error.pwa.restore.reauthUnsupported");
    }

    @Test
    @WithMockUser(username = "user-a", roles = "要員")
    void confirmもパスワード再認証に対応しないsessionは専用codeの403で返す() throws Exception {
        when(authorizationService.confirm(any(), any(), any()))
                .thenThrow(new PwaRestoreReauthUnsupportedException());
        mockMvc.perform(post("/api/my/pwa/restore/confirm").with(csrf())
                        .session(new MockHttpSession()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"proof":"p","documentNonce":"AAAAAAAAAAAAAAAAAAAAAA","userScope":"scope"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data.type").value("PWA_RESTORE_REAUTH_UNSUPPORTED"));
    }
}
