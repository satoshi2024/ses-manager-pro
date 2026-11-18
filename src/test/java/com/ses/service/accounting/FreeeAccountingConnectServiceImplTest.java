package com.ses.service.accounting;

import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.dto.freee.FreeeTokenGrant;
import com.ses.entity.IntegrationConnection;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.accounting.impl.FreeeAccountingConnectServiceImpl;
import com.ses.service.integration.IntegrationConnectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * freee会計接続のOAuth認可確定（m_integration_connectionへのtoken保存）。
 *
 * <ul>
 *   <li>code交換は給与側の共通処理へ委譲し、会計 users/me で事業所を検証してから保存する</li>
 *   <li>事業所が所属一覧に無い・self_only・既に別事業所へ紐付く接続は保存しない</li>
 *   <li>他tenant・freee以外の接続は認可を開始させない</li>
 * </ul>
 */
@DisplayName("freee会計接続 OAuth認可確定")
class FreeeAccountingConnectServiceImplTest {

    private static final String USERS_ME = "https://api.freee.co.jp/api/1/users/me?companies=true";
    private static final FreeeTokenGrant GRANT =
            new FreeeTokenGrant("fixture-access-token", "fixture-refresh-token", 21600L, 123L);

    private FreeeIntegrationService freeeIntegrationService;
    private IntegrationConnectionService connectionService;
    private MockRestServiceServer server;
    private FreeeAccountingConnectServiceImpl service;

    @BeforeEach
    void setUp() {
        freeeIntegrationService = mock(FreeeIntegrationService.class);
        connectionService = mock(IntegrationConnectionService.class);
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new FreeeAccountingConnectServiceImpl(freeeIntegrationService, connectionService, restTemplate);
        ReflectionTestUtils.setField(service, "apiBaseUrl", "https://api.freee.co.jp");
        AccountingTenantContextHolder.setTenantId("default");
        when(freeeIntegrationService.exchangeAuthorizationCode("fixture-code")).thenReturn(GRANT);
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    private IntegrationConnection connection(Long boundCompanyId) {
        IntegrationConnection c = new IntegrationConnection();
        c.setId(1L);
        c.setTenantId("default");
        c.setProvider("freee");
        c.setProduct("accounting");
        c.setStatus("DISCONNECTED");
        c.setExternalCompanyId(boundCompanyId);
        when(connectionService.getByIdScoped(eq(1L), eq("default"), isNull())).thenReturn(c);
        return c;
    }

    private static String usersMe(String role) {
        return "{\"user\":{\"id\":9,\"companies\":["
                + "{\"id\":999,\"display_name\":\"他社\",\"role\":\"admin\"},"
                + "{\"id\":123,\"display_name\":\"テスト事業所\",\"role\":\"" + role + "\"}]}}";
    }

    @Test
    @DisplayName("成功: 会計users/meで事業所を確認し、token・事業所ID・事業所名を保存する")
    void 成功時は会計接続へ保存する() {
        connection(null);
        server.expect(once(), requestTo(USERS_ME))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer fixture-access-token"))
                .andRespond(withSuccess(usersMe("admin"), MediaType.APPLICATION_JSON));

        service.completeAuthorization(1L, "fixture-code", 7L);
        server.verify();

        ArgumentCaptor<IntegrationTokensDto> tokens = ArgumentCaptor.forClass(IntegrationTokensDto.class);
        verify(connectionService).saveTokens(eq(1L), tokens.capture(), eq(123L), eq("テスト事業所"), eq(7L));
        assertEquals("fixture-access-token", tokens.getValue().getAccessToken());
        assertEquals("fixture-refresh-token", tokens.getValue().getRefreshToken());
        assertEquals(21600L, tokens.getValue().getExpiresIn());
    }

    @Test
    @DisplayName("同一事業所への再認可は許可する")
    void 同一事業所の再認可は保存する() {
        connection(123L);
        server.expect(once(), requestTo(USERS_ME))
                .andRespond(withSuccess(usersMe("simple_accounting"), MediaType.APPLICATION_JSON));

        service.completeAuthorization(1L, "fixture-code", 7L);

        verify(connectionService).saveTokens(eq(1L), any(), eq(123L), eq("テスト事業所"), eq(7L));
    }

    @Test
    @DisplayName("既に別事業所へ紐付く接続は上書きしない（マッピングの外部IDが事業所固有のため）")
    void 別事業所の接続は上書きしない() {
        connection(555L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.completeAuthorization(1L, "fixture-code", 7L));
        assertEquals("error.accounting.freeeCompanyMismatch", ex.getMessage());
        verify(connectionService, never()).saveTokens(anyLong(), any(), any(), any(), any());
        server.verify(); // users/meも呼ばない
    }

    @Test
    @DisplayName("選択事業所がusers/meの所属一覧に無ければ保存しない")
    void 所属外の事業所は保存しない() {
        connection(null);
        server.expect(once(), requestTo(USERS_ME))
                .andRespond(withSuccess("{\"user\":{\"companies\":[{\"id\":999,\"display_name\":\"他社\",\"role\":\"admin\"}]}}",
                        MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.completeAuthorization(1L, "fixture-code", 7L));
        assertEquals("error.payroll.companyMismatch", ex.getMessage());
        verify(connectionService, never()).saveTokens(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("self_only権限では事業所データを扱えないため保存しない")
    void self_onlyは保存しない() {
        connection(null);
        server.expect(once(), requestTo(USERS_ME))
                .andRespond(withSuccess(usersMe("self_only"), MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.completeAuthorization(1L, "fixture-code", 7L));
        assertEquals("error.accounting.freeePermissionDenied", ex.getMessage());
        verify(connectionService, never()).saveTokens(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("users/meが403なら権限不足として保存しない")
    void users_meの403は権限不足() {
        connection(null);
        server.expect(once(), requestTo(USERS_ME)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.completeAuthorization(1L, "fixture-code", 7L));
        assertEquals("error.accounting.freeePermissionDenied", ex.getMessage());
        verify(connectionService, never()).saveTokens(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("code交換失敗はそのまま伝播し、users/meも保存も行わない")
    void code交換失敗は保存しない() {
        connection(null);
        when(freeeIntegrationService.exchangeAuthorizationCode(anyString()))
                .thenThrow(BusinessException.of("error.payroll.oauthFailed"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.completeAuthorization(1L, "bad-code", 7L));
        assertEquals("error.payroll.oauthFailed", ex.getMessage());
        verify(connectionService, never()).saveTokens(anyLong(), any(), any(), any(), any());
        server.verify();
    }

    @Test
    @DisplayName("他tenant・存在しない・freee以外の接続は認可を開始させない（404）")
    void 対象外の接続は404() {
        // tenant境界外（getByIdScopedがnull）
        BusinessException notFound = assertThrows(BusinessException.class,
                () -> service.requireConnectable(1L));
        assertEquals(404, notFound.getCode());
        assertEquals("error.accounting.connectionNotFound", notFound.getMessage());

        // freee以外のprovider
        IntegrationConnection csv = connection(null);
        csv.setProvider("csv");
        BusinessException notFreee = assertThrows(BusinessException.class,
                () -> service.requireConnectable(1L));
        assertEquals(404, notFreee.getCode());

        // ID欠落
        assertThrows(BusinessException.class, () -> service.requireConnectable(null));
        verify(freeeIntegrationService, never()).exchangeAuthorizationCode(anyString());
    }
}
