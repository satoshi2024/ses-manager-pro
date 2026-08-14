package com.ses.web;

import com.ses.controller.api.FreeeOAuthController;
import com.ses.service.FreeeIntegrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HFP-01-003: OAuth callbackのstate検証（TTL・一回性・拒否callback）をMockMvcで検証する。
 *
 * <ul>
 *   <li>state欠落・不一致・期限切れ・再送ではtoken交換（service.handleCallback）を呼ばない（AC02）</li>
 *   <li>freee側の認可拒否callback（error=access_denied）でも呼ばない</li>
 *   <li>正常時は1回だけ呼び、/payroll?connected=1へredirect（code/stateを載せない）</li>
 *   <li>authorizeは24byte stateをsessionへ保存してから公式認可URLへredirect</li>
 * </ul>
 */
@WebMvcTest(controllers = FreeeOAuthController.class)
@DisplayName("HFP-01-003 OAuth callback state検証")
class FreeeOAuthCallbackWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FreeeIntegrationService service;

    private static final String CALLBACK =
            "https://accounts.secure.freee.co.jp/public_api/authorize?response_type=code"
                    + "&client_id=c&redirect_uri=r&state=STATE&prompt=select_company";

    private String startOAuth() throws Exception {
        org.mockito.Mockito.when(service.authorizationUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CALLBACK);
        MvcResult result = mockMvc.perform(get("/integrations/freee/authorize"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(CALLBACK))
                .andReturn();
        org.springframework.mock.web.MockHttpSession session =
                (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession();
        assertNotNull(session.getAttribute(FreeeOAuthController.SESSION_STATE), "sessionへstateが保存されるはず");
        assertNotNull(session.getAttribute(FreeeOAuthController.SESSION_STATE_ISSUED), "発行時刻が保存されるはず");
        return session.getAttribute(FreeeOAuthController.SESSION_STATE).toString();
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("正常callback: state一致・期限内でtoken交換1回・connected=1へredirect")
    void 正常callbackは1回だけ処理する() throws Exception {
        String state = startOAuth();
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE, state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE_ISSUED,
                                java.time.Instant.now().getEpochSecond()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/payroll?connected=1"));
        verify(service, times(1)).handleCallback(
                org.mockito.ArgumentMatchers.eq("fixture-code"),
                org.mockito.ArgumentMatchers.eq(state),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("state不一致はtoken交換しない")
    void state不一致は処理しない() throws Exception {
        startOAuth();
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", "WRONG-STATE"))
                .andExpect(redirectedUrl("/payroll?error=state"));
        verify(service, never()).handleCallback(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("state欠落はtoken交換しない")
    void state欠落は処理しない() throws Exception {
        startOAuth();
        mockMvc.perform(get("/integrations/freee/callback").param("code", "fixture-code"))
                .andExpect(redirectedUrl("/payroll?error=state"));
        verify(service, never()).handleCallback(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("期限切れstate（10分超）はtoken交換しない")
    void 期限切れstateは処理しない() throws Exception {
        String state = startOAuth();
        long expired = java.time.Instant.now().getEpochSecond() - (FreeeOAuthController.STATE_TTL_SECONDS + 60);
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE, state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE_ISSUED, expired))
                .andExpect(redirectedUrl("/payroll?error=state"));
        verify(service, never()).handleCallback(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("再送（同一stateの二回目）はtoken交換しない")
    void state再送は処理しない() throws Exception {
        String state = startOAuth();
        org.springframework.mock.web.MockHttpSession session =
                new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(FreeeOAuthController.SESSION_STATE, state);
        session.setAttribute(FreeeOAuthController.SESSION_STATE_ISSUED,
                java.time.Instant.now().getEpochSecond());

        // 一回目: 同一session・state一致 → 成功
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", state)
                        .session(session))
                .andExpect(redirectedUrl("/payroll?connected=1"));

        // 二回目: 同一session（stateは一回目で除去済み） → 拒否
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", state)
                        .session(session))
                .andExpect(redirectedUrl("/payroll?error=state"));

        verify(service, times(1)).handleCallback(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("freee側の認可拒否callback（error=access_denied）はtoken交換しない")
    void 認可拒否callbackは処理しない() throws Exception {
        String state = startOAuth();
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("error", "access_denied")
                        .param("state", state))
                .andExpect(redirectedUrl("/payroll?error=denied"));
        verify(service, never()).handleCallback(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("設定不足時は認可開始を拒否しconfig errorへredirect")
    void 設定不足で認可開始を拒否する() throws Exception {
        org.mockito.Mockito.when(service.authorizationUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(com.ses.common.exception.BusinessException.of("error.payroll.configIncomplete"));
        mockMvc.perform(get("/integrations/freee/authorize"))
                .andExpect(redirectedUrl("/payroll?error=config"));
    }
}
