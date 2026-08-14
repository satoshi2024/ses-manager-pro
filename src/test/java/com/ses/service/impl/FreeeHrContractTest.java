package com.ses.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.common.exception.BusinessException;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.entity.FreeeConnection;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.FreeeConnectionMapper;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.service.FreeeIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.test.web.client.match.MockRestRequestMatchers;

/**
 * HFP-01-004: Typed HR contract / pagination / error matrix。
 *
 * <ul>
 *   <li>employees 0/1/100/101/200・100件ちょうどの追加空page（AC06）</li>
 *   <li>salary/bonus 0/1/100/101/200・total_count pagination（AC07）</li>
 *   <li>反復ID・途中空page・total変化・root欠落・invalid amount→有限時間でcontract error（AC09）</li>
 *   <li>未知field許容・計算中null保持（AC07）</li>
 *   <li>401 code分類・429 Retry-After・5xx/timeout bounded retry（AC10）</li>
 * </ul>
 */
@DisplayName("HFP-01-004 HR contract adapter / pagination / error matrix")
class FreeeHrContractTest {

    private static final String HR_BASE = "https://api.freee.co.jp/hr";
    private static final String EMPLOYEES = HR_BASE + "/api/v1/companies/123/employees";
    private static final String SALARY = HR_BASE + "/api/v1/salaries/employee_payroll_statements";
    private static final String BONUS = HR_BASE + "/api/v1/bonuses/employee_payroll_statements";
    private static final String TOKEN_URL = "https://accounts.secure.freee.co.jp/public_api/token";

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
        ReflectionTestUtils.setField(service, "apiBase", "https://api.freee.co.jp");
        ReflectionTestUtils.setField(service, "oauthBase", "https://accounts.secure.freee.co.jp/public_api");
        ReflectionTestUtils.setField(service, "hrApiBase", HR_BASE);
        ReflectionTestUtils.setField(service, "clientId", "fixture-client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "fixture-client-secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8080/integrations/freee/callback");
        ReflectionTestUtils.setField(service, "encryptionKey", "change-me-change-me-change-me-1234");
        ReflectionTestUtils.setField(service, "activeProfile", "test");
        ReflectionTestUtils.setField(service, "sleeper", (FreeeIntegrationServiceImpl.Sleeper) millis -> {
        });
        when(applicationContext.getBean(FreeeIntegrationService.class)).thenReturn(service);

        when(linkMapper.selectList(any())).thenReturn(seedLink());
        when(engineerMapper.selectList(any())).thenReturn(seedEngineers());
    }

    /** 現在companyの有効link（employee 501 → engineer 7）。 */
    private java.util.List<com.ses.entity.FreeeEmployeeLink> seedLink() {
        com.ses.entity.FreeeEmployeeLink link = new com.ses.entity.FreeeEmployeeLink();
        link.setEngineerId(7L);
        link.setFreeeEmployeeId("501");
        link.setFreeeCompanyId(123L);
        return java.util.List.of(link);
    }

    /** 非BPの内部要員。 */
    private java.util.List<com.ses.entity.Engineer> seedEngineers() {
        com.ses.entity.Engineer e = new com.ses.entity.Engineer();
        e.setId(7L);
        e.setFullName("テスト要員7");
        e.setEmploymentType("正社員");
        return java.util.List.of(e);
    }

    private void seedConnection() throws Exception {
        FreeeConnection c = new FreeeConnection();
        c.setId(1L);
        c.setCompanyId(123L);
        c.setAccessTokenEncrypted(encrypt("fixture-access-token"));
        c.setRefreshTokenEncrypted(encrypt("fixture-refresh-token"));
        c.setTokenExpiresAt(LocalDateTime.now().plusHours(1));
        c.setConnectionStatus("CONNECTED");
        when(connectionMapper.selectOne(any())).thenReturn(c);
        when(connectionMapper.selectLatestForUpdate()).thenReturn(c);
    }

    private String encrypt(String plain) throws Exception {
        Method m = FreeeIntegrationServiceImpl.class.getDeclaredMethod("encrypt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, plain);
    }

    // ============ fixture builders（個人データなし・架空ID） ============

    private ArrayNode employees(int fromId, int count) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (int i = 0; i < count; i++) {
            int id = fromId + i;
            ObjectNode n = objectMapper.createObjectNode();
            n.put("id", id);
            n.put("num", "E-" + id);
            n.put("display_name", "従業員" + id);
            n.put("entry_date", "2020-04-01");
            n.putNull("retire_date");
            n.put("payroll_calculation", true);
            arr.add(n);
        }
        return arr;
    }

    private ObjectNode salaryPage(int fromId, int count, int totalCount) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("employee_payroll_statements");
        for (int i = 0; i < count; i++) {
            int id = fromId + i;
            ObjectNode n = objectMapper.createObjectNode();
            n.put("id", id);
            n.put("company_id", 123);
            n.put("employee_id", 501);
            n.put("employee_num", "E-501");
            n.put("pay_date", "2026-07-25");
            n.put("fixed", true);
            n.put("calc_status", "calculated");
            n.put("gross_payment_amount", "250000");
            n.put("total_deduction_amount", "50000");
            n.put("net_payment_amount", "200000");
            arr.add(n);
        }
        root.put("total_count", totalCount);
        return root;
    }

    private ObjectNode bonusPage(int fromId, int count, int totalCount) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("employee_payroll_statements");
        for (int i = 0; i < count; i++) {
            int id = fromId + i;
            ObjectNode n = objectMapper.createObjectNode();
            n.put("id", id);
            n.put("company_id", 123);
            n.put("employee_id", 501);
            n.put("employee_num", "E-501");
            n.put("pay_date", "2026-07-10");
            n.put("fixed", true);
            n.put("calc_status", "calculated");
            n.put("gross_payment_amount", "500000");
            n.put("total_deduction_amount", "100000");
            n.put("net_payment_amount", "400000");
            arr.add(n);
        }
        root.put("total_count", totalCount);
        return root;
    }

    private String employeesUrl(int offset) {
        return EMPLOYEES + "?with_no_payroll_calculation=true&limit=100&offset=" + offset;
    }

    private String statementUrl(String base, int offset) {
        return base + "?company_id=123&year=2026&month=7&limit=100&offset=" + offset;
    }

    /** query値（順序非依存）でemployees GETをmatcherする。 */
    private org.springframework.test.web.client.RequestMatcher employeesQuery(int offset) {
        return request -> {
            MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.startsWith(EMPLOYEES + "?")).match(request);
            MockRestRequestMatchers.queryParam("with_no_payroll_calculation", "true").match(request);
            MockRestRequestMatchers.queryParam("limit", "100").match(request);
            MockRestRequestMatchers.queryParam("offset", String.valueOf(offset)).match(request);
        };
    }

    /** query値（順序非依存）でsalary/bonus一覧GETをmatcherする。 */
    private org.springframework.test.web.client.RequestMatcher statementQuery(String base, int offset) {
        return request -> {
            MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.startsWith(base + "?")).match(request);
            MockRestRequestMatchers.queryParam("company_id", "123").match(request);
            MockRestRequestMatchers.queryParam("year", "2026").match(request);
            MockRestRequestMatchers.queryParam("month", "7").match(request);
            MockRestRequestMatchers.queryParam("limit", "100").match(request);
            MockRestRequestMatchers.queryParam("offset", String.valueOf(offset)).match(request);
        };
    }

    // ============ employees pagination ============

    @Test
    @DisplayName("employees: 0件→空（AC06）")
    void employeesの0件() throws Exception {
        seedConnection();
        server.expect(once(), employeesQuery(0))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertTrue(service.employees().isEmpty());
    }

    @Test
    @DisplayName("employees: 1件→1件（AC06）")
    void employeesの1件() throws Exception {
        seedConnection();
        server.expect(once(), employeesQuery(0))
                .andRespond(withSuccess(employees(501, 1).toString(), MediaType.APPLICATION_JSON));
        List<FreeeEmployeeDto> one = service.employees();
        assertEquals(1, one.size());
        assertEquals("501", one.get(0).getId());
    }

    @Test
    @DisplayName("employees: 100件ちょうどは追加空page 1回で終了（AC06）")
    void employeesの100件ちょうど() throws Exception {
        seedConnection();
        server.expect(once(), employeesQuery(0))
                .andRespond(withSuccess(employees(501, 100).toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), employeesQuery(100))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<FreeeEmployeeDto> all = service.employees();
        assertEquals(100, all.size());
        server.verify();
    }

    @Test
    @DisplayName("employees: 101件は2pageで欠落・重複なく取得（AC06/AC09）")
    void employeesの101件() throws Exception {
        seedConnection();
        server.expect(once(), employeesQuery(0))
                .andRespond(withSuccess(employees(501, 100).toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), employeesQuery(100))
                .andRespond(withSuccess(employees(601, 1).toString(), MediaType.APPLICATION_JSON));

        List<FreeeEmployeeDto> all = service.employees();
        assertEquals(101, all.size());
        assertEquals(101, all.stream().map(FreeeEmployeeDto::getId).distinct().count());
    }

    @Test
    @DisplayName("employees: 200件は2page（100+100）+空pageで終了（AC06）")
    void employeesの200件() throws Exception {
        seedConnection();
        server.expect(once(), employeesQuery(0))
                .andRespond(withSuccess(employees(501, 100).toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), employeesQuery(100))
                .andRespond(withSuccess(employees(601, 100).toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), employeesQuery(200))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<FreeeEmployeeDto> all = service.employees();
        assertEquals(200, all.size());
        assertEquals(200, all.stream().map(FreeeEmployeeDto::getId).distinct().count());
    }

    // ============ salary / bonus pagination ============

    @Test
    @DisplayName("salary: 0件はtotal_count=0+空配列だけが正常（AC09）")
    void salaryの0件() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess("{\"employee_payroll_statements\":[],\"total_count\":0}",
                        MediaType.APPLICATION_JSON));
        assertTrue(service.statements(2026, 7, "salary").isEmpty());
    }

    @Test
    @DisplayName("salary: 101件は2page（100+1）、total_count一致（AC06/AC07）")
    void salaryの101件() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess(salaryPage(9001, 100, 101).toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), statementQuery(SALARY, 100))
                .andRespond(withSuccess(salaryPage(9101, 1, 101).toString(), MediaType.APPLICATION_JSON));

        List<PayrollStatementDto> all = service.statements(2026, 7, "salary");
        assertEquals(101, all.size());
        assertEquals(new BigDecimal("250000"), all.get(0).getGrossAmount());
        assertEquals(new BigDecimal("50000"), all.get(0).getDeductionAmount());
        assertEquals(new BigDecimal("200000"), all.get(0).getNetAmount());
    }

    @Test
    @DisplayName("bonus: 101件を公式endpointから取得（AC07）")
    void bonusの101件() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(BONUS, 0))
                .andRespond(withSuccess(bonusPage(9101, 100, 101).toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), statementQuery(BONUS, 100))
                .andRespond(withSuccess(bonusPage(9201, 1, 101).toString(), MediaType.APPLICATION_JSON));

        List<PayrollStatementDto> all = service.statements(2026, 7, "bonus");
        assertEquals(101, all.size());
        assertEquals("bonus", all.get(0).getType());
    }

    // ============ schema drift / contract error ============

    @Test
    @DisplayName("page内・既出IDの反復はcontract error（AC09）")
    void salaryのID反復はcontractError() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess(salaryPage(9001, 2, 3).toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), statementQuery(SALARY, 2))
                .andRespond(withSuccess(salaryPage(9001, 1, 3).toString(), MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals(502, ex.getCode());
        assertEquals("error.payroll.contractError", ex.getMessage());
    }

    @Test
    @DisplayName("途中の空pageはcontract error（空結果にしない）（AC09）")
    void salaryの途中空pageはcontractError() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess(salaryPage(9001, 100, 150).toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), statementQuery(SALARY, 100))
                .andRespond(withSuccess("{\"employee_payroll_statements\":[],\"total_count\":150}",
                        MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals("error.payroll.contractError", ex.getMessage());
    }

    @Test
    @DisplayName("total_count変化はcontract error（AC09）")
    void salaryのtotal変化はcontractError() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess(salaryPage(9001, 100, 101).toString(), MediaType.APPLICATION_JSON));
        server.expect(once(), statementQuery(SALARY, 100))
                .andRespond(withSuccess(salaryPage(9101, 1, 102).toString(), MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals("error.payroll.contractError", ex.getMessage());
    }

    @Test
    @DisplayName("HTTP 200でroot欠落は空扱いせずcontract error（AC09/R01-3）")
    void root欠落はcontractError() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess("{\"unexpected_root\":[]}", MediaType.APPLICATION_JSON));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals("error.payroll.contractError", ex.getMessage());
    }

    @Test
    @DisplayName("employeesのrootがwrapper objectならcontract error（AC09）")
    void employeesのroot不正はcontractError() throws Exception {
        seedConnection();
        server.expect(once(), employeesQuery(0))
                .andRespond(withSuccess("{\"employees\":[]}", MediaType.APPLICATION_JSON));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.employees());
        assertEquals("error.payroll.contractError", ex.getMessage());
    }

    @Test
    @DisplayName("未知fieldは許容し、計算中null金額は0へ変換しない（AC07/R05-6）")
    void 未知field許容とnull保持() throws Exception {
        seedConnection();
        String body = "{\"employee_payroll_statements\":["
                + "{\"id\":9001,\"company_id\":123,\"employee_id\":501,\"employee_num\":\"E-501\","
                + "\"fixed\":false,\"calc_status\":\"calculating\","
                + "\"gross_payment_amount\":null,\"total_deduction_amount\":null,\"net_payment_amount\":null,"
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[],"
                + "\"future_unknown_field\":\"ignored\"}],\"total_count\":1}";
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<PayrollStatementDto> all = service.statements(2026, 7, "salary");
        assertEquals(1, all.size());
        assertNull(all.get(0).getGrossAmount(), "計算中のnull金額は0へ変換しない");
        assertNull(all.get(0).getDeductionAmount());
        assertNull(all.get(0).getNetAmount());
    }

    @Test
    @DisplayName("invalid amount（非数値）はcontract error（AC07/AC09）")
    void invalidAmountはcontractError() throws Exception {
        seedConnection();
        String body = "{\"employee_payroll_statements\":["
                + "{\"id\":9001,\"company_id\":123,\"employee_id\":501,\"employee_num\":\"E-501\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"abc\",\"total_deduction_amount\":null,\"net_payment_amount\":null}],"
                + "\"total_count\":1}";
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals("error.payroll.contractError", ex.getMessage());
    }

    @Test
    @DisplayName("pagination上限到達はcontract error（AC09/R06-4）")
    void pagination上限はcontractError() throws Exception {
        seedConnection();
        ReflectionTestUtils.setField(service, "maxPages", 3);
        // 全pageが100件ちょうど（raw配列・totalなし）→ 上限到達で失敗
        server.expect(times(3), requestTo(org.hamcrest.Matchers.startsWith(EMPLOYEES)))
                .andRespond(withSuccess(employees(501, 100).toString(), MediaType.APPLICATION_JSON));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.employees());
        assertEquals("error.payroll.contractError", ex.getMessage());
    }

    // ============ error matrix ============

    @Test
    @DisplayName("401 expired_access_tokenはrefresh 1回＋元GET 1回（AC10）")
    void 期限切れ401はrefresh1回で回復する() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"access_denied\",\"code\":\"expired_access_token\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"fixture-access-token-2\","
                        + "\"refresh_token\":\"fixture-refresh-token-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess(salaryPage(9001, 1, 1).toString(), MediaType.APPLICATION_JSON));

        List<PayrollStatementDto> all = service.statements(2026, 7, "salary");
        assertEquals(1, all.size());
        server.verify();
    }

    @Test
    @DisplayName("401 re_authorization_requiredはREAUTH_REQUIREDへ遷移し自動refreshしない（AC10）")
    void 再認可要求401はREAUTH_REQUIRED() throws Exception {
        FreeeConnection c = seedConnectionWithCaptor();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"access_denied\",\"code\":\"re_authorization_required\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals("error.payroll.reauthRequired", ex.getMessage());
        assertEquals("REAUTH_REQUIRED", c.getConnectionStatus());
        server.verify(); // token endpointへはPOSTしていない
    }

    @Test
    @DisplayName("401 user_do_not_have_permissionはretryせず403（AC10）")
    void ユーザー権限不足401はrefreshしない() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"access_denied\",\"code\":\"user_do_not_have_permission\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals(403, ex.getCode());
        assertEquals("error.payroll.permissionDenied", ex.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("403/404はretryせず分類message（AC10）")
    void フォビドゥン403はretryしない() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals(403, ex.getCode());
        assertEquals("error.payroll.permissionDenied", ex.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("404はretryせずnotFound（AC10）")
    void ノットファウンド404はretryしない() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals(404, ex.getCode());
        assertEquals("error.payroll.notFound", ex.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("429はRetry-Afterを尊重し最大3回（AC10）")
    void レート制限429はRetryAfterを尊重する() throws Exception {
        seedConnection();
        server.expect(times(3), statementQuery(SALARY, 0))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "1"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals(429, ex.getCode());
        assertEquals("error.payroll.rateLimited", ex.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("429はRetry-After後に再試行で成功する（AC10）")
    void レート制限429後に成功する() throws Exception {
        seedConnection();
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "1"));
        server.expect(once(), statementQuery(SALARY, 0))
                .andRespond(withSuccess(salaryPage(9001, 1, 1).toString(), MediaType.APPLICATION_JSON));
        assertEquals(1, service.statements(2026, 7, "salary").size());
        server.verify();
    }

    @Test
    @DisplayName("HR GET 5xxは最大2回retry後に503（AC10）")
    void サーバー5xxは2回retry後に503() throws Exception {
        seedConnection();
        server.expect(times(3), statementQuery(SALARY, 0))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals(503, ex.getCode());
        assertEquals("error.payroll.providerUnavailable", ex.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("HR GET timeoutは最大2回retry後に503（AC10）")
    void タイムアウトは2回retry後に503() throws Exception {
        seedConnection();
        server.expect(times(3), statementQuery(SALARY, 0))
                .andRespond(request -> {
                    throw new org.springframework.web.client.ResourceAccessException("Read timed out");
                });
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.statements(2026, 7, "salary"));
        assertEquals(503, ex.getCode());
        server.verify();
    }

    @Test
    @DisplayName("S11 apiGetは5xx/timeoutを従来どおり即503にする（retry混入なし）")
    void apiGetの5xxはretryしない() throws Exception {
        seedConnection();
        server.expect(once(), requestTo("https://api.freee.co.jp/hr/api/v1/attendance/updated"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.apiGet("/hr/api/v1/attendance/updated"));
        assertEquals(503, ex.getCode());
        server.verify();
    }

    private FreeeConnection seedConnectionWithCaptor() throws Exception {
        FreeeConnection c = new FreeeConnection();
        c.setId(1L);
        c.setCompanyId(123L);
        c.setAccessTokenEncrypted(encrypt("fixture-access-token"));
        c.setRefreshTokenEncrypted(encrypt("fixture-refresh-token"));
        c.setTokenExpiresAt(LocalDateTime.now().plusHours(1));
        c.setConnectionStatus("CONNECTED");
        when(connectionMapper.selectOne(any())).thenReturn(c);
        when(connectionMapper.selectLatestForUpdate()).thenReturn(c);
        org.mockito.Mockito.doAnswer(invocation -> {
            c.setConnectionStatus("REAUTH_REQUIRED");
            return 1;
        }).when(connectionMapper).updateById(any(FreeeConnection.class));
        return c;
    }
}
