package com.ses.web;

import com.ses.controller.api.FreeeOAuthController;
import com.ses.service.AuditLogService;
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

    @MockBean
    private AuditLogService auditLogService;

    @MockBean
    private com.ses.service.accounting.FreeeAccountingConnectService accountingConnectService;

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

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("給与callbackの業務失敗は内部message keyだけをreasonとして返す（provider詳細は載せない）")
    void 給与callback失敗はreasonを返す() throws Exception {
        String state = startOAuth();
        org.mockito.Mockito.doThrow(com.ses.common.exception.BusinessException.of("error.payroll.companyNotAdmin"))
                .when(service).handleCallback(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE, state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE_ISSUED,
                                java.time.Instant.now().getEpochSecond()))
                .andExpect(redirectedUrl("/payroll?error=oauth&reason=error.payroll.companyNotAdmin"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("自システム以外の文言（provider由来等）はreasonへ載せない")
    void 想定外messageはreasonへ載せない() throws Exception {
        String state = startOAuth();
        org.mockito.Mockito.doThrow(new com.ses.common.exception.BusinessException(400, "invalid_grant: raw provider text"))
                .when(service).handleCallback(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE, state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE_ISSUED,
                                java.time.Instant.now().getEpochSecond()))
                .andExpect(redirectedUrl("/payroll?error=oauth"));
    }

    // ============ 会計接続（target=accounting） ============

    private org.springframework.mock.web.MockHttpSession startAccountingOAuth() throws Exception {
        org.mockito.Mockito.when(service.authorizationUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CALLBACK);
        MvcResult result = mockMvc.perform(get("/integrations/freee/authorize")
                        .param("target", "accounting")
                        .param("connectionId", "5"))
                .andExpect(redirectedUrl(CALLBACK))
                .andReturn();
        org.springframework.mock.web.MockHttpSession session =
                (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession();
        org.junit.jupiter.api.Assertions.assertEquals(FreeeOAuthController.TARGET_ACCOUNTING,
                session.getAttribute(FreeeOAuthController.SESSION_TARGET));
        org.junit.jupiter.api.Assertions.assertEquals(5L,
                session.getAttribute(FreeeOAuthController.SESSION_CONNECTION_ID));
        verify(accountingConnectService).requireConnectable(5L);
        return session;
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("会計: 認可対象をsessionへ保存し、callbackで会計接続へ保存・会計画面へ戻る")
    void 会計callbackは会計接続へ保存する() throws Exception {
        org.springframework.mock.web.MockHttpSession session = startAccountingOAuth();
        String state = session.getAttribute(FreeeOAuthController.SESSION_STATE).toString();

        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", state)
                        .session(session))
                .andExpect(redirectedUrl("/accounting/integration?freee_connected=1"));

        verify(accountingConnectService, times(1)).completeAuthorization(
                org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq("fixture-code"),
                org.mockito.ArgumentMatchers.any());
        verify(service, never()).handleCallback(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("GET"), org.mockito.ArgumentMatchers.eq("/integrations/freee"),
                org.mockito.ArgumentMatchers.eq(302), org.mockito.ArgumentMatchers.eq("FREEE_ACCOUNTING_CONNECT"),
                org.mockito.ArgumentMatchers.eq(true));
        // 認可対象はcallbackで消費され、次の給与callbackへ持ち越さない
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(FreeeOAuthController.SESSION_TARGET));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(FreeeOAuthController.SESSION_CONNECTION_ID));
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("会計: 業務失敗は会計画面へreason付きで戻り、失敗監査する")
    void 会計callback失敗は会計画面へ戻る() throws Exception {
        org.springframework.mock.web.MockHttpSession session = startAccountingOAuth();
        String state = session.getAttribute(FreeeOAuthController.SESSION_STATE).toString();
        org.mockito.Mockito.doThrow(com.ses.common.exception.BusinessException.of("error.accounting.freeeCompanyMismatch"))
                .when(accountingConnectService).completeAuthorization(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", state)
                        .session(session))
                .andExpect(redirectedUrl(
                        "/accounting/integration?freee_error=oauth&reason=error.accounting.freeeCompanyMismatch"));
        verify(auditLogService).record(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("GET"), org.mockito.ArgumentMatchers.eq("/integrations/freee"),
                org.mockito.ArgumentMatchers.eq(400), org.mockito.ArgumentMatchers.eq("FREEE_ACCOUNTING_CONNECT"),
                org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("会計: state不一致は会計画面へ戻り、token交換しない")
    void 会計callbackのstate不一致は処理しない() throws Exception {
        org.springframework.mock.web.MockHttpSession session = startAccountingOAuth();
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", "WRONG-STATE")
                        .session(session))
                .andExpect(redirectedUrl("/accounting/integration?freee_error=state"));
        verify(accountingConnectService, never()).completeAuthorization(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("会計: 対象外の接続IDでは認可を開始せずstateも発行しない")
    void 会計の対象外接続は認可開始しない() throws Exception {
        org.mockito.Mockito.when(accountingConnectService.requireConnectable(99L))
                .thenThrow(com.ses.common.exception.BusinessException.of(404, "error.accounting.connectionNotFound"));
        MvcResult result = mockMvc.perform(get("/integrations/freee/authorize")
                        .param("target", "accounting")
                        .param("connectionId", "99"))
                .andExpect(redirectedUrl(
                        "/accounting/integration?freee_error=connection&reason=error.accounting.connectionNotFound"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertNull(
                result.getRequest().getSession().getAttribute(FreeeOAuthController.SESSION_STATE));
        verify(service, never()).authorizationUrl(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("給与の認可開始は前回の会計対象をsessionから消す（給与callbackを会計へ誤保存しない）")
    void 給与の認可開始は会計対象を消す() throws Exception {
        org.mockito.Mockito.when(service.authorizationUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CALLBACK);
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(FreeeOAuthController.SESSION_TARGET, FreeeOAuthController.TARGET_ACCOUNTING);
        session.setAttribute(FreeeOAuthController.SESSION_CONNECTION_ID, 5L);
        mockMvc.perform(get("/integrations/freee/authorize").session(session))
                .andExpect(redirectedUrl(CALLBACK));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(FreeeOAuthController.SESSION_TARGET));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(FreeeOAuthController.SESSION_CONNECTION_ID));
    }

    @Test
    @WithMockUser(roles = "管理者", username = "audit-admin")
    @DisplayName("handleCallbackの非業務例外（DB障害等）でもFREEE_CONNECT失敗監査を記録する（REV-008）")
    void handleCallbackのDB障害でも失敗監査する() throws Exception {
        String state = startOAuth();
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(service).handleCallback(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        mockMvc.perform(get("/integrations/freee/callback")
                        .param("code", "fixture-code")
                        .param("state", state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE, state)
                        .sessionAttr(FreeeOAuthController.SESSION_STATE_ISSUED,
                                java.time.Instant.now().getEpochSecond()))
                .andExpect(status().is5xxServerError());
        verify(auditLogService).record(
                org.mockito.ArgumentMatchers.eq("audit-admin"),
                org.mockito.ArgumentMatchers.eq("GET"),
                org.mockito.ArgumentMatchers.eq("/integrations/freee"),
                org.mockito.ArgumentMatchers.eq(500),
                org.mockito.ArgumentMatchers.eq("FREEE_CONNECT"),
                org.mockito.ArgumentMatchers.eq(false));
    }
}
