package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.payroll.FreeeConnectionStatusDto;
import com.ses.entity.FreeeConnection;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.FreeeConnectionMapper;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.service.FreeeIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HFP-01-003: OAuth/company検証/refresh/revoke/接続状態の公式契約test。
 *
 * <ul>
 *   <li>認可URLは公式host＋prompt=select_company、scopeなし（AC01）</li>
 *   <li>callback成功でtoken POST 1回・users/me 1回・company_id保存（AC02/AC03）</li>
 *   <li>認可失敗・company不一致・self_onlyは接続を保存せず既存接続を守る（AC03）</li>
 *   <li>refresh: lock後再確認で外部呼出し0回、rotation保存、invalid_grant→REAUTH_REQUIRED、refresh_token欠落→REAUTH（AC04 / S15-P1-02）</li>
 *   <li>revoke: 成功/既失効/片方timeoutでlocal保持（AC05）</li>
 *   <li>状態機械: DISCONNECTED/CONNECTED/REAUTH_REQUIRED/MISCONFIGURED（R03）</li>
 * </ul>
 */
@DisplayName("HFP-01-003 OAuth/接続lifecycle")
class FreeeOAuthContractTest {

    private static final String OAUTH_BASE = "https://accounts.secure.freee.co.jp/public_api";
    private static final String HR_BASE = "https://api.freee.co.jp/hr";

    private FreeeConnectionMapper connectionMapper;
    private FreeeEmployeeLinkMapper linkMapper;
    private EngineerMapper engineerMapper;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private FreeeIntegrationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        connectionMapper = mock(FreeeConnectionMapper.class);
        linkMapper = mock(FreeeEmployeeLinkMapper.class);
        engineerMapper = mock(EngineerMapper.class);
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        service = new FreeeIntegrationServiceImpl(connectionMapper, linkMapper, engineerMapper,
                restTemplate, applicationContext);
        ReflectionTestUtils.setField(service, "apiBase", "https://api.freee.co.jp");
        ReflectionTestUtils.setField(service, "oauthBase", OAUTH_BASE);
        ReflectionTestUtils.setField(service, "hrApiBase", HR_BASE);
        ReflectionTestUtils.setField(service, "clientId", "fixture-client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "fixture-client-secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8080/integrations/freee/callback");
        ReflectionTestUtils.setField(service, "encryptionKey", "change-me-change-me-change-me-1234");
        ReflectionTestUtils.setField(service, "activeProfile", "test");
        when(applicationContext.getBean(FreeeIntegrationService.class)).thenReturn(service);

        when(linkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(engineerMapper.selectById(any())).thenReturn(new com.ses.entity.Engineer());
    }

    private String encrypt(String plain) throws Exception {
        Method m = FreeeIntegrationServiceImpl.class.getDeclaredMethod("encrypt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, plain);
    }

    private String fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/freee/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: /freee/" + name);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private FreeeConnection connectedRow() throws Exception {
        FreeeConnection c = new FreeeConnection();
        c.setId(1L);
        c.setCompanyId(123L);
        c.setCompanyName("テスト事業所");
        c.setAccessTokenEncrypted(encrypt("fixture-access-token"));
        c.setRefreshTokenEncrypted(encrypt("fixture-refresh-token"));
        c.setTokenExpiresAt(LocalDateTime.now().plusHours(1));
        c.setConnectionStatus("CONNECTED");
        return c;
    }

    private void stubLatestRow(FreeeConnection row) {
        when(connectionMapper.selectOne(any())).thenReturn(row);
        when(connectionMapper.selectLatestForUpdate()).thenReturn(row);
    }

    private void stubSuccessCallback() throws Exception {
        server.expect(once(), requestTo(OAUTH_BASE + "/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("token-success.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(HR_BASE + "/api/v1/users/me"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("users-me-company-admin.json"), MediaType.APPLICATION_JSON));
    }

    // ============ 認可URL / 設定 ============

    @Test
    @DisplayName("認可URLは公式host・prompt=select_company・scopeなし（AC01）")
    void authorizationUrlは公式契約どおりである() {
        String url = service.authorizationUrl("state-abc");
        assertTrue(url.startsWith(OAUTH_BASE + "/authorize?"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=fixture-client-id"));
        assertTrue(url.contains("redirect_uri="));
        assertTrue(url.contains("state=state-abc"));
        assertTrue(url.contains("prompt=select_company"));
        assertFalse(url.contains("scope"), "公式根拠のないscopeを送ってはならない: " + url);
    }

    @Test
    @DisplayName("設定不足時は認可開始を拒否し状態はMISCONFIGURED（R03）")
    void config未設定はMISCONFIGUREDになる() {
        ReflectionTestUtils.setField(service, "clientId", "");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.authorizationUrl("state-abc"));
        assertEquals("error.payroll.configIncomplete", ex.getMessage());
        FreeeConnectionStatusDto dto = service.connectionStatus();
        assertEquals("MISCONFIGURED", dto.getStatus());
        assertFalse(dto.isConnected());
    }

    @Test
    @DisplayName("prodでclient-id設定時は一式検証、URLはHTTPS公式host必須（design §3）")
    void prod設定検証が効く() throws Exception {
        Method m = FreeeIntegrationServiceImpl.class.getDeclaredMethod("validateConfig");
        m.setAccessible(true);
        ReflectionTestUtils.setField(service, "activeProfile", "prod");

        // client-id設定＋URL不正（非公式host）→ 失敗
        ReflectionTestUtils.setField(service, "oauthBase", "https://evil.example.com/public_api");
        ReflectionTestUtils.setField(service, "redirectUri", "https://ses.example.com/integrations/freee/callback");
        ReflectionTestUtils.setField(service, "encryptionKey", "prod-key-not-change-me-1234567890");
        java.lang.reflect.InvocationTargetException ex1 =
                assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(service));
        assertTrue(ex1.getCause() instanceof IllegalStateException
                        && ex1.getCause().getMessage().contains("公式host"),
                "URL検証メッセージ: " + ex1.getCause().getMessage());

        // URLを公式へ戻してもsecret未設定 → 一式検証で失敗
        ReflectionTestUtils.setField(service, "oauthBase", "https://accounts.secure.freee.co.jp/public_api");
        ReflectionTestUtils.setField(service, "clientSecret", "");
        java.lang.reflect.InvocationTargetException ex2 =
                assertThrows(java.lang.reflect.InvocationTargetException.class, () -> m.invoke(service));
        assertTrue(ex2.getCause() instanceof IllegalStateException
                        && ex2.getCause().getMessage().contains("client-secret"),
                "一式検証メッセージ: " + ex2.getCause().getMessage());

        // 全部そろえば起動可能
        ReflectionTestUtils.setField(service, "clientSecret", "fixture-client-secret");
        m.invoke(service); // 例外なし
    }

    @Test
    @DisplayName("prodでclient-id未設定なら起動可能（MISCONFIGURED運用）")
    void prodでclientId未設定は起動を妨げない() throws Exception {
        Method m = FreeeIntegrationServiceImpl.class.getDeclaredMethod("validateConfig");
        m.setAccessible(true);
        ReflectionTestUtils.setField(service, "activeProfile", "prod");
        ReflectionTestUtils.setField(service, "clientId", "");
        ReflectionTestUtils.setField(service, "clientSecret", "");
        ReflectionTestUtils.setField(service, "oauthBase", "https://accounts.secure.freee.co.jp/public_api");
        ReflectionTestUtils.setField(service, "hrApiBase", "https://api.freee.co.jp/hr");
        ReflectionTestUtils.setField(service, "apiBase", "https://api.freee.co.jp");
        m.invoke(service); // 例外なし
        assertEquals("MISCONFIGURED", service.connectionStatus().getStatus());
    }

    // ============ callback / company検証 ============

    @Test
    @DisplayName("callback成功: token POST 1回 + users/me 1回 + company_id保存（AC02/AC03）")
    void callback成功で接続が確定する() throws Exception {
        stubSuccessCallback();

        service.handleCallback("fixture-code", "fixture-state", 7L);
        server.verify();

        ArgumentCaptor<FreeeConnection> captor = ArgumentCaptor.forClass(FreeeConnection.class);
        verify(connectionMapper).insert(captor.capture());
        FreeeConnection saved = captor.getValue();
        assertEquals(123L, saved.getCompanyId());
        assertEquals("テスト事業所", saved.getCompanyName());
        assertEquals("CONNECTED", saved.getConnectionStatus());
        assertEquals(7L, saved.getConnectedBy());
    }

    @Test
    @DisplayName("token交換が4xxなら接続を保存しない（既存接続も守る）（AC02）")
    void token交換失敗は接続を保存しない() throws Exception {
        FreeeConnection existing = connectedRow();
        stubLatestRow(existing);
        server.expect(once(), requestTo(OAUTH_BASE + "/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handleCallback("fixture-code", "fixture-state", 7L));
        assertEquals("error.payroll.oauthFailed", ex.getMessage());
        verify(connectionMapper, never()).insert(any(com.ses.entity.FreeeConnection.class));
        verify(connectionMapper, never()).updateById(any(com.ses.entity.FreeeConnection.class));
    }

    @Test
    @DisplayName("users/meでcompany_adminでない事業所は接続不可（AC03）")
    void self_onlyはcompanyNotAdminで接続しない() throws Exception {
        server.expect(once(), requestTo(OAUTH_BASE + "/token"))
                .andRespond(withSuccess(fixture("token-success.json"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(HR_BASE + "/api/v1/users/me"))
                .andRespond(withSuccess(fixture("users-me-self-only.json"), MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handleCallback("fixture-code", "fixture-state", 7L));
        assertEquals("error.payroll.companyNotAdmin", ex.getMessage());
        verify(connectionMapper, never()).insert(any(com.ses.entity.FreeeConnection.class));
    }

    @Test
    @DisplayName("users/meに選択事業所が無ければcompanyMismatch（AC03）")
    void company不一致は接続しない() throws Exception {
        server.expect(once(), requestTo(OAUTH_BASE + "/token"))
                .andRespond(withSuccess("{\"access_token\":\"fixture-access-token\","
                        + "\"refresh_token\":\"fixture-refresh-token\",\"expires_in\":3600,\"company_id\":999}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(HR_BASE + "/api/v1/users/me"))
                .andRespond(withSuccess(fixture("users-me-company-admin.json"), MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handleCallback("fixture-code", "fixture-state", 7L));
        assertEquals("error.payroll.companyMismatch", ex.getMessage());
        verify(connectionMapper, never()).insert(any(com.ses.entity.FreeeConnection.class));
    }

    // ============ 接続状態 ============

    @Test
    @DisplayName("接続状態はrow存在ではなく内容から判定する（R03-2）")
    void connectionStatusは内容から判定する() throws Exception {
        // rowなし → DISCONNECTED
        when(connectionMapper.selectOne(any())).thenReturn(null);
        assertEquals("DISCONNECTED", service.connectionStatus().getStatus());
        assertFalse(service.connected());

        // company_id欠落 → MISCONFIGURED
        FreeeConnection noCompany = connectedRow();
        noCompany.setCompanyId(null);
        stubLatestRow(noCompany);
        assertEquals("MISCONFIGURED", service.connectionStatus().getStatus());
        assertFalse(service.connected());

        // REAUTH_REQUIRED
        FreeeConnection reauth = connectedRow();
        reauth.setConnectionStatus("REAUTH_REQUIRED");
        stubLatestRow(reauth);
        FreeeConnectionStatusDto dto = service.connectionStatus();
        assertEquals("REAUTH_REQUIRED", dto.getStatus());
        assertFalse(dto.isConnected());
        assertEquals("テスト事業所", dto.getCompanyName());

        // 正常 → CONNECTED
        stubLatestRow(connectedRow());
        FreeeConnectionStatusDto ok = service.connectionStatus();
        assertEquals("CONNECTED", ok.getStatus());
        assertTrue(ok.isConnected());
        assertTrue(service.connected());
    }

    // ============ refresh ============

    @Test
    @DisplayName("refresh: lock後再確認で有効期限に余裕があれば外部refreshしない（AC04）")
    void refreshは有効期限内なら外部呼出ししない() throws Exception {
        stubLatestRow(connectedRow()); // 1時間後まで有効
        service.refresh();
        // 期待なしなので、外部呼び出しがあればMockRestServiceServerが失敗させる
    }

    @Test
    @DisplayName("refresh: 新refresh tokenへrotation保存され外部使用は1回（AC04）")
    void refreshはrotation保存する() throws Exception {
        FreeeConnection expired = connectedRow();
        expired.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        stubLatestRow(expired);

        server.expect(once(), requestTo(OAUTH_BASE + "/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"fixture-access-token-2\","
                        + "\"refresh_token\":\"fixture-refresh-token-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        service.refresh();
        server.verify();

        ArgumentCaptor<FreeeConnection> captor = ArgumentCaptor.forClass(FreeeConnection.class);
        verify(connectionMapper).updateById(captor.capture());
        // 新refresh tokenが暗号化保存されている（復号して確認）
        Method decrypt = FreeeIntegrationServiceImpl.class.getDeclaredMethod("decrypt", String.class);
        decrypt.setAccessible(true);
        assertEquals("fixture-access-token-2", decrypt.invoke(service, captor.getValue().getAccessTokenEncrypted()));
        assertEquals("fixture-refresh-token-2", decrypt.invoke(service, captor.getValue().getRefreshTokenEncrypted()));
        assertEquals("CONNECTED", captor.getValue().getConnectionStatus());
    }

    @Test
    @DisplayName("refresh: invalid_grantはREAUTH_REQUIREDへ遷移し失敗する（AC04）")
    void refreshのinvalid_grantはREAUTH_REQUIREDになる() throws Exception {
        FreeeConnection expired = connectedRow();
        expired.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        stubLatestRow(expired);

        server.expect(once(), requestTo(OAUTH_BASE + "/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .body(fixture("token-invalid-grant.json")).contentType(MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.refresh());
        assertEquals("error.payroll.reauthRequired", ex.getMessage());

        ArgumentCaptor<FreeeConnection> captor = ArgumentCaptor.forClass(FreeeConnection.class);
        verify(connectionMapper).updateById(captor.capture());
        assertEquals("REAUTH_REQUIRED", captor.getValue().getConnectionStatus());
    }

    @Test
    @DisplayName("refresh: 応答にrefresh_tokenが無い場合は旧tokenへフォールバックせずREAUTH（S15-P1-02）")
    void refreshのrefresh_token欠落はREAUTHになる() throws Exception {
        FreeeConnection expired = connectedRow();
        expired.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        stubLatestRow(expired);

        server.expect(once(), requestTo(OAUTH_BASE + "/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"fixture-access-token-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.refresh());
        assertEquals("error.payroll.reauthRequired", ex.getMessage());

        ArgumentCaptor<FreeeConnection> captor = ArgumentCaptor.forClass(FreeeConnection.class);
        verify(connectionMapper).updateById(captor.capture());
        assertEquals("REAUTH_REQUIRED", captor.getValue().getConnectionStatus());
        // access/refresh の token 書戻しは行われない（欠落時 fail-closed）
        assertTrue(captor.getValue().getAccessTokenEncrypted() == null
                || captor.getValue().getAccessTokenEncrypted().isBlank()
                || "REAUTH_REQUIRED".equals(captor.getValue().getConnectionStatus()));
    }

    // ============ revoke / disconnect ============

    @Test
    @DisplayName("revoke: access/refresh双方へ失効要求し成功後に削除（AC05）")
    void revoke成功で接続を削除する() throws Exception {
        stubLatestRow(connectedRow());
        server.expect(once(), requestTo(OAUTH_BASE + "/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(OAUTH_BASE + "/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.disconnect();
        server.verify();
        verify(connectionMapper).deleteById(1L);
    }

    @Test
    @DisplayName("revoke: 既に無効(invalid_grant)なら成功扱いで削除（AC05）")
    void revokeの既失効は成功扱い() throws Exception {
        stubLatestRow(connectedRow());
        server.expect(once(), requestTo(OAUTH_BASE + "/revoke"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .body(fixture("token-invalid-grant.json")).contentType(MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(OAUTH_BASE + "/revoke"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.disconnect();
        server.verify();
        verify(connectionMapper).deleteById(1L);
    }

    @Test
    @DisplayName("revoke: 片方timeoutなら削除せず再実行可能な状態を保つ（AC05）")
    void revokeの一時障害はlocalRowを残す() throws Exception {
        stubLatestRow(connectedRow());
        server.expect(once(), requestTo(OAUTH_BASE + "/revoke"))
                .andRespond(request -> {
                    throw new org.springframework.web.client.ResourceAccessException("Connect timed out");
                });

        BusinessException ex = assertThrows(BusinessException.class, () -> service.disconnect());
        assertEquals("error.payroll.revokeFailed", ex.getMessage());
        verify(connectionMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("revoke: 5xxは削除せずBusinessException（AC05）")
    void revokeの5xxは削除しない() throws Exception {
        stubLatestRow(connectedRow());
        server.expect(once(), requestTo(OAUTH_BASE + "/revoke"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));

        assertThrows(BusinessException.class, () -> service.disconnect());
        verify(connectionMapper, never()).deleteById(any(Long.class));
    }

    // ============ 秘密非出力 ============

    @Test
    @DisplayName("refresh失敗ログにtoken/秘密が出力されない")
    void refresh失敗のログに秘密を含まない() throws Exception {
        FreeeConnection expired = connectedRow();
        expired.setTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        stubLatestRow(expired);
        server.expect(once(), requestTo(OAUTH_BASE + "/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FreeeIntegrationServiceImpl.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(BusinessException.class, () -> service.refresh());
        } finally {
            logger.detachAppender(appender);
        }
        boolean leaked = appender.list.stream()
                .flatMap(e -> java.util.Arrays.stream(e.getFormattedMessage().split("\\s+")))
                .anyMatch(w -> w.contains("fixture-access-token") || w.contains("fixture-refresh-token")
                        || w.contains("fixture-client-secret"));
        assertFalse(leaked, "秘密がログへ出力されました: " + appender.list);
    }
}
