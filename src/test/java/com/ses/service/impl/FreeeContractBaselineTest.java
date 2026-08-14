package com.ses.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.entity.FreeeConnection;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.entity.Engineer;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HFP-01-001 baseline: 現行実装が公式契約から逸脱していることを証明する失敗test。
 *
 * <p>正本はresearch.mdに固定したfreee人事労務OpenAPI commit
 * （52c69a6819ef14979a31b342123df816cb72c742）。このclassはproduction codeを変更せず、
 * 公式契約をassertするため、現行実装では**意図した理由でredになる**。</p>
 *
 * <ul>
 *   <li>OAuth host / scope / prompt</li>
 *   <li>company_id保存・指定</li>
 *   <li>employees path / root / BP判定</li>
 *   <li>salary path / root / field / null / items</li>
 * </ul>
 */
@DisplayName("HFP-01-001 baseline: 現行実装の公式契約逸脱の再現（redが正しい）")
class FreeeContractBaselineTest {

    private static final String OFFICIAL_OAUTH_HOST = "https://accounts.secure.freee.co.jp/public_api";
    private static final String OFFICIAL_OAUTH_AUTHORIZE = OFFICIAL_OAUTH_HOST + "/authorize";
    private static final String OFFICIAL_OAUTH_TOKEN = OFFICIAL_OAUTH_HOST + "/token";
    private static final String HR_BASE = "https://api.freee.co.jp/hr";

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        // 現行実装の既定config（api-base-url がOAuth/HR/会計を兼用している状態）
        ReflectionTestUtils.setField(service, "apiBase", "https://api.freee.co.jp");
        ReflectionTestUtils.setField(service, "oauthBase", OFFICIAL_OAUTH_HOST);
        ReflectionTestUtils.setField(service, "hrApiBase", HR_BASE);
        ReflectionTestUtils.setField(service, "clientId", "fixture-client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "fixture-client-secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8080/integrations/freee/callback");
        ReflectionTestUtils.setField(service, "encryptionKey", "change-me-change-me-change-me-1234");
        ReflectionTestUtils.setField(service, "activeProfile", "test");
        when(applicationContext.getBean(FreeeIntegrationService.class)).thenReturn(service);

        when(linkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(engineerMapper.selectById(any())).thenReturn(new Engineer());
    }

    private void seedConnection() throws Exception {
        FreeeConnection connection = new FreeeConnection();
        connection.setId(1L);
        connection.setCompanyId(123L);
        connection.setAccessTokenEncrypted(encrypt("fixture-access-token"));
        connection.setRefreshTokenEncrypted(encrypt("fixture-refresh-token"));
        connection.setTokenExpiresAt(LocalDateTime.now().plusHours(1));
        when(connectionMapper.selectOne(any())).thenReturn(connection);
        when(connectionMapper.selectLatestForUpdate()).thenReturn(connection);
    }

    private String encrypt(String plain) throws Exception {
        Method m = FreeeIntegrationServiceImpl.class.getDeclaredMethod("encrypt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, plain);
    }

    private JsonNode fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/freee/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: /freee/" + name);
            }
            return objectMapper.readTree(in);
        }
    }

    // ============ OAuth / company ============

    @Test
    @DisplayName("authorizationUrlは公式host・prompt=select_companyを使いscopeを付けない（AC01）")
    void authorizationUrlは公式OAuth契約に従う() {
        String url = service.authorizationUrl("state-baseline");
        assertTrue(url.startsWith(OFFICIAL_OAUTH_AUTHORIZE),
                "認可URLが公式hostでない: " + url);
        assertTrue(url.contains("prompt=select_company"),
                "prompt=select_company が認可URLに含まれない: " + url);
        assertFalse(url.contains("scope="),
                "公式根拠のないscope queryが送られる: " + url);
    }

    @Test
    @DisplayName("handleCallbackは公式token URLへPOSTする（AC01）")
    void handleCallbackは公式tokenURLを使う() throws Exception {
        server.expect(once(), requestTo(OFFICIAL_OAUTH_TOKEN))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("token-success.json").toString(), MediaType.APPLICATION_JSON));
        // users/me検証（R02-4）
        server.expect(once(), requestTo(HR_BASE + "/api/v1/users/me"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("users-me-company-admin.json").toString(), MediaType.APPLICATION_JSON));

        service.handleCallback("fixture-code", "fixture-state", 1L);
        server.verify();
    }

    @Test
    @DisplayName("handleCallbackはtoken responseのcompany_idを保存する（AC03）")
    void handleCallbackはcompany_idを保存する() throws Exception {
        server.expect(once(), requestTo(OFFICIAL_OAUTH_TOKEN))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("token-success.json").toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(HR_BASE + "/api/v1/users/me"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("users-me-company-admin.json").toString(), MediaType.APPLICATION_JSON));

        service.handleCallback("fixture-code", "fixture-state", 1L);

        ArgumentCaptor<FreeeConnection> captor = ArgumentCaptor.forClass(FreeeConnection.class);
        org.mockito.Mockito.verify(connectionMapper).insert(captor.capture());
        assertEquals(123L, captor.getValue().getCompanyId(),
                "token responseのcompany_idが保存されない");
    }

    // ============ employees ============

    @Test
    @DisplayName("employeesは公式company pathをcompany_id/limit/offset付きでGETする（AC06）")
    void employeesは公式companyPathを使う() throws Exception {
        seedConnection();
        String official = HR_BASE + "/api/v1/companies/123/employees"
                + "?with_no_payroll_calculation=true&limit=100&offset=0";
        server.expect(once(), requestTo(official))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("employees-page1.json").toString(), MediaType.APPLICATION_JSON));

        service.employees();
        server.verify();
    }

    @Test
    @DisplayName("employeesは公式raw配列を解析する（R04/R01-3）")
    void employeesは公式rawArrayを返す() throws Exception {
        seedConnection();
        server.expect(once(), requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(fixture("employees-page1.json").toString(), MediaType.APPLICATION_JSON));

        java.util.List<FreeeEmployeeDto> employees = service.employees();
        assertEquals(3, employees.size(),
                "公式root（raw配列）から3件取得できるはず。現行は旧root解析のため0件になる");
    }

    @Test
    @DisplayName("BP判定はfreeeのemployment_typeに依存しない（R04-5）")
    void employeesはfreeeのemploymentTypeでBP除外しない() throws Exception {
        seedConnection();
        server.expect(once(), requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(fixture("employees-legacy-wrapped.json").toString(), MediaType.APPLICATION_JSON));

        java.util.List<FreeeEmployeeDto> employees = service.employees();
        assertEquals(3, employees.size(),
                "公式enumに存在しないemployment_type=BPは未知propertyとして無視されるべき（現行は除外して2件になる）");
    }

    // ============ statements ============

    @Test
    @DisplayName("statementsは公式salary endpointへcompany_id/year/month/limit/offsetでGETする（AC01/AC07）")
    void statementsは公式salaryEndpointを使う() throws Exception {
        seedConnection();
        String official = HR_BASE + "/api/v1/salaries/employee_payroll_statements"
                + "?company_id=123&year=2026&month=7&limit=100&offset=0";
        server.expect(once(), requestTo(official))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("salary-calculated.json").toString(), MediaType.APPLICATION_JSON));

        service.statements(2026, 7, "salary");
        server.verify();
    }

    @Test
    @DisplayName("statementsは公式root/field名から変換する（AC07）")
    void statementsは公式rootとfieldで変換する() throws Exception {
        seedConnection();
        server.expect(once(), requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(fixture("salary-calculated.json").toString(), MediaType.APPLICATION_JSON));

        java.util.List<PayrollStatementDto> statements = service.statements(2026, 7, "salary");
        assertEquals(1, statements.size(),
                "公式root employee_payroll_statements から1件変換できるはず（現行は旧root statementsで0件）");
        assertEquals(new BigDecimal("250000"), statements.get(0).getGrossAmount(),
                "公式field gross_payment_amount から変換できるはず（現行は旧field gross_amountでnull）");
    }

    @Test
    @DisplayName("計算中のnull金額を0へ変換しない（AC07/R05-6）")
    void statementsは計算中nullを0へ変換しない() throws Exception {
        seedConnection();
        server.expect(once(), requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(fixture("statements-legacy-null.json").toString(), MediaType.APPLICATION_JSON));

        java.util.List<PayrollStatementDto> statements = service.statements(2026, 7, "salary");
        assertEquals(1, statements.size());
        assertNull(statements.get(0).getGrossAmount(),
                "null金額はnullのまま保持されるべき（現行はBigDecimal.ZEROへ変換する）");
        assertNull(statements.get(0).getDeductions(),
                "null控除はnullのまま保持されるべき");
    }

    @Test
    @DisplayName("区分付き明細itemsを返す（AC07/R05-5）")
    void statementsは区分付きitemsを返す() throws Exception {
        seedConnection();
        server.expect(once(), requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(fixture("statements-legacy-items.json").toString(), MediaType.APPLICATION_JSON));

        java.util.List<PayrollStatementDto> statements = service.statements(2026, 7, "salary");
        assertEquals(1, statements.size());
        org.junit.jupiter.api.Assertions.assertNotNull(statements.get(0).getItems(),
                "支給・控除・会社負担の区分付き明細を返すべき（現行はitemsを一切設定しない）");
    }
}
