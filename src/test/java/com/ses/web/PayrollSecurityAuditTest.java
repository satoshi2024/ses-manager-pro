package com.ses.web;

import com.ses.service.FreeeIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HFP-01-007: 静的権限・CSRF・no-store・機微GET監査（1 request 1 row・禁止値0）。
 *
 * <ul>
 *   <li>管理者/HR/営業/マネージャー/要員/未認証のpage・API・OAuth matrix（AC11）</li>
 *   <li>PUT/DELETEはCSRFなし403、ありでroleに応じ成功</li>
 *   <li>全GET response headerがno-store（AC12）</li>
 *   <li>給与・賞与・従業員・link/unlink・接続/解除が各1 rowで監査され、禁止値が無い（AC12）</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("HFP-01-007 payroll security / cache / audit")
@Transactional
class PayrollSecurityAuditTest {

    private static final String SALARY_URL =
            "https://api.freee.co.jp/hr/api/v1/salaries/employee_payroll_statements?";
    private static final String EMPLOYEES_URL =
            "https://api.freee.co.jp/hr/api/v1/companies/123/employees?";
    private static final String TOKEN_URL = "https://accounts.secure.freee.co.jp/public_api/token";
    private static final String REVOKE_URL = "https://accounts.secure.freee.co.jp/public_api/revoke";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("saasRestTemplate")
    private RestTemplate restTemplate;

    @Autowired
    private FreeeIntegrationService freeeIntegrationService;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = MockRestServiceServer.createServer(restTemplate);
        // 本testが使うデータだけを挿入（共有H2のため、読む行は自分が挿入した行に限定）
        jdbcTemplate.update("DELETE FROM t_freee_employee_link WHERE engineer_id = 90001");
        jdbcTemplate.update("DELETE FROM t_freee_connection WHERE company_id = 123");
        jdbcTemplate.update("DELETE FROM t_engineer WHERE id = 90001");
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type, status) "
                + "VALUES (90001, '監査用要員', '正社員', '稼動中')");
        String token = encrypt("fixture-access-token");
        String refresh = encrypt("fixture-refresh-token");
        jdbcTemplate.update("INSERT INTO t_freee_connection "
                + "(company_id, company_name, access_token_encrypted, refresh_token_encrypted, "
                + "token_expires_at, connection_status) "
                + "VALUES (123, 'テスト事業所', ?, ?, DATEADD('MINUTE', 60, CURRENT_TIMESTAMP), 'CONNECTED')",
                token, refresh);
    }

    private String encrypt(String plain) throws Exception {
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(freeeIntegrationService);
        Method m = target.getClass().getDeclaredMethod("encrypt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(target, plain);
    }

    private void seedLink() {
        jdbcTemplate.update("INSERT INTO t_freee_employee_link "
                + "(engineer_id, freee_employee_id, freee_company_id, confirmed_by) "
                + "VALUES (90001, '501', 123, 1)");
    }

    private void expectSalaryPage() throws Exception {
        String body = "{\"employee_payroll_statements\":["
                + "{\"id\":9001,\"company_id\":123,\"employee_id\":501,\"employee_num\":\"E-501\","
                + "\"pay_date\":\"2026-07-25\",\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"250000\",\"total_deduction_amount\":\"50000\","
                + "\"net_payment_amount\":\"200000\","
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]}],\"total_count\":1}";
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                requestTo(org.hamcrest.Matchers.startsWith(SALARY_URL)))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectEmployeesPage() throws Exception {
        String body = "[{\"id\":501,\"num\":\"E-501\",\"display_name\":\"従業員甲\","
                + "\"entry_date\":\"2020-04-01\",\"retire_date\":null,\"payroll_calculation\":true}]";
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                requestTo(org.hamcrest.Matchers.startsWith(EMPLOYEES_URL)))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private long auditMaxId() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM t_audit_log", Long.class);
        return max == null ? 0 : max;
    }

    private List<java.util.Map<String, Object>> auditRowsAfter(long minId) {
        return jdbcTemplate.queryForList(
                "SELECT username, method, uri, status, application_code, success_flag "
                        + "FROM t_audit_log WHERE id > ? ORDER BY id", minId);
    }

    // ============ role matrix ============

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("管理者はpage/API/OAuthへアクセスできる")
    void 管理者はアクセスできる() throws Exception {
        mockMvc.perform(get("/payroll")).andExpect(status().isOk());
        mockMvc.perform(get("/api/payroll/status")).andExpect(status().isOk());
        mockMvc.perform(get("/integrations/freee/authorize")).andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "HR")
    @DisplayName("HRはpage/APIへアクセスできOAuthは403")
    void HRは給与参照できOAuthは不可() throws Exception {
        mockMvc.perform(get("/payroll")).andExpect(status().isOk());
        mockMvc.perform(get("/api/payroll/status")).andExpect(status().isOk());
        mockMvc.perform(get("/integrations/freee/authorize")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "営業")
    @DisplayName("営業はpage/API/OAuthすべて403")
    void 営業は全て403() throws Exception {
        mockMvc.perform(get("/payroll")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payroll/status")).andExpect(status().isForbidden());
        mockMvc.perform(get("/integrations/freee/authorize")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    @DisplayName("マネージャーはpage/API/OAuthすべて403")
    void マネージャーは全て403() throws Exception {
        mockMvc.perform(get("/payroll")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payroll/status")).andExpect(status().isForbidden());
        mockMvc.perform(get("/integrations/freee/authorize")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "要員")
    @DisplayName("要員はpage/API/OAuthすべて403")
    void 要員は全て403() throws Exception {
        mockMvc.perform(get("/payroll")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payroll/status")).andExpect(status().isForbidden());
        mockMvc.perform(get("/integrations/freee/authorize")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("未認証はpage/APIとも401（本アプリの既存契約）")
    void 未認証は拒否される() throws Exception {
        mockMvc.perform(get("/payroll")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/payroll/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/integrations/freee/authorize")).andExpect(status().isUnauthorized());
    }

    // ============ CSRF ============

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("CSRFなしのPUT/DELETEは403、ありは成功")
    void csrfなし403あり成功() throws Exception {
        seedLink();
        // CSRFなし: controllerへ到達しないためprovider呼出しも発生しない
        mockMvc.perform(put("/api/payroll/links/90001").param("employeeId", "501"))
                .andExpect(status().isForbidden());

        // CSRF付きでlink成功（provider: employees page 1回）
        expectEmployeesPage();
        mockMvc.perform(put("/api/payroll/links/90001").param("employeeId", "501").with(csrf()))
                .andExpect(status().isOk());
        server.verify();
    }

    // ============ no-store ============

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("page/API/OAuthの全GETがno-store")
    void 全GETはnoStore() throws Exception {
        expectEmployeesPage();
        expectSalaryPage();
        mockMvc.perform(get("/payroll"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"));
        mockMvc.perform(get("/api/payroll/status"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/api/payroll/employees"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/api/payroll/engineer-candidates"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/api/payroll/statements").param("year", "2026").param("month", "7"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/integrations/freee/authorize"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    // ============ audit ============

    @Test
    @WithMockUser(roles = "管理者", username = "audit-admin")
    @DisplayName("給与・賞与・従業員参照が各1 rowで監査され年月/typeがcodeに入る")
    void 機微GETは1request1rowで監査される() throws Exception {
        // 期待は先に全部登録する（MockRestServiceServerはrequest後にexpect不可）
        expectSalaryPage();
        String bonusBody = "{\"employee_payroll_statements\":["
                + "{\"id\":9101,\"company_id\":123,\"employee_id\":501,\"employee_num\":\"E-501\","
                + "\"pay_date\":\"2026-07-10\",\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"500000\",\"total_deduction_amount\":\"100000\","
                + "\"net_payment_amount\":\"400000\","
                + "\"allowances\":[],\"deductions\":[]}],\"total_count\":1}";
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                requestTo(org.hamcrest.Matchers.startsWith(
                        "https://api.freee.co.jp/hr/api/v1/bonuses/employee_payroll_statements?")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(bonusBody, MediaType.APPLICATION_JSON));
        expectEmployeesPage();

        long before = auditMaxId();
        mockMvc.perform(get("/api/payroll/statements").param("year", "2026").param("month", "7"))
                .andExpect(status().isOk());
        List<java.util.Map<String, Object>> rows = auditRowsAfter(before);
        assertEquals(1, rows.size(), "1 request = 1 audit row");
        assertEquals("PAYROLL_SALARY_VIEW_202607", rows.get(0).get("application_code"));
        assertEquals("/api/payroll/statements", rows.get(0).get("uri"));
        assertEquals("GET", rows.get(0).get("method"));
        assertEquals("audit-admin", rows.get(0).get("username"));

        before = auditMaxId();
        mockMvc.perform(get("/api/payroll/statements").param("year", "2026").param("month", "7")
                        .param("type", "bonus"))
                .andExpect(status().isOk());
        rows = auditRowsAfter(before);
        assertEquals(1, rows.size());
        assertEquals("PAYROLL_BONUS_VIEW_202607", rows.get(0).get("application_code"));

        before = auditMaxId();
        mockMvc.perform(get("/api/payroll/employees")).andExpect(status().isOk());
        rows = auditRowsAfter(before);
        assertEquals(1, rows.size());
        assertEquals("PAYROLL_EMPLOYEE_VIEW", rows.get(0).get("application_code"));
        server.verify();
    }

    @Test
    @WithMockUser(roles = "管理者", username = "audit-admin")
    @DisplayName("link/unlinkが各1 rowで監査されIDをURIへ載せない")
    void linkUnlinkは1request1rowで監査される() throws Exception {
        seedLink();
        expectEmployeesPage();
        long before = auditMaxId();
        mockMvc.perform(put("/api/payroll/links/90001").param("employeeId", "501").with(csrf()))
                .andExpect(status().isOk());
        List<java.util.Map<String, Object>> rows = auditRowsAfter(before);
        assertEquals(1, rows.size(), "linkは1 rowのみ（ApiAuditFilter二重記録なし）");
        assertEquals("PAYROLL_LINK", rows.get(0).get("application_code"));
        assertEquals("/api/payroll/links", rows.get(0).get("uri"));
        assertTrue(!rows.get(0).get("uri").toString().contains("90001"), "URIにengineer IDを載せない");

        before = auditMaxId();
        mockMvc.perform(delete("/api/payroll/links/90001").with(csrf()))
                .andExpect(status().isOk());
        rows = auditRowsAfter(before);
        assertEquals(1, rows.size());
        assertEquals("PAYROLL_UNLINK", rows.get(0).get("application_code"));
    }

    @Test
    @WithMockUser(roles = "管理者", username = "audit-admin")
    @DisplayName("接続解除がFREEE_DISCONNECTで監査される")
    void 接続解除は監査される() throws Exception {
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2),
                requestTo(org.hamcrest.Matchers.startsWith(REVOKE_URL)))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        long before = auditMaxId();
        mockMvc.perform(delete("/integrations/freee").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        List<java.util.Map<String, Object>> rows = auditRowsAfter(before);
        assertEquals(1, rows.size());
        assertEquals("FREEE_DISCONNECT", rows.get(0).get("application_code"));
        assertEquals("/integrations/freee", rows.get(0).get("uri"));
    }

    @Test
    @WithMockUser(roles = "管理者", username = "audit-admin")
    @DisplayName("監査rowに金額・氏名・外部ID・tokenが残らない")
    void 監査rowに禁止値が無い() throws Exception {
        seedLink();
        expectEmployeesPage(); // GET employees
        expectSalaryPage();    // GET statements
        expectEmployeesPage(); // PUT link（employee存在検証）
        long before = auditMaxId();
        mockMvc.perform(get("/api/payroll/employees")).andExpect(status().isOk());
        mockMvc.perform(get("/api/payroll/statements").param("year", "2026").param("month", "7"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/payroll/links/90001").param("employeeId", "501").with(csrf()))
                .andExpect(status().isOk());
        server.verify();

        List<java.util.Map<String, Object>> rows = auditRowsAfter(before);
        assertTrue(rows.size() >= 3);
        for (java.util.Map<String, Object> row : rows) {
            String serialized = row.values().toString();
            for (String forbidden : new String[]{"250000", "50000", "200000", "監査用要員", "従業員甲",
                    "501", "90001", "fixture-access-token", "fixture-refresh-token", "E-501"}) {
                assertTrue(!serialized.contains(forbidden),
                        "監査rowへ禁止値が混入: " + forbidden + " in " + serialized);
            }
        }
    }

    @Test
    @WithMockUser(roles = "管理者", username = "audit-admin")
    @DisplayName("失敗系も各1 rowで監査されsuccess_flag=false・生金額が漏れない（REV-003/REV-001）")
    void 失敗系も1request1rowで監査される() throws Exception {
        // MockRestServiceServerはrequest後にexpect追加不可のため、先に全て登録する

        // 1) statements: provider 5xx（hrGetは最大2回retry=計3リクエスト）→ 失敗監査
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3),
                requestTo(org.hamcrest.Matchers.startsWith(SALARY_URL)))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY)
                        .body("{\"status_code\":500}").contentType(MediaType.APPLICATION_JSON));

        // 2) link: BP要員への対応付け（employee存在検証でemployees 1回）→ 失敗監査
        expectEmployeesPage();

        // 3) disconnect: revokeが5xx → 解除せず失敗監査
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                requestTo(org.hamcrest.Matchers.startsWith(REVOKE_URL)))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));

        // 1) statements
        long before = auditMaxId();
        org.springframework.test.web.servlet.MvcResult failureResult = mockMvc.perform(
                        get("/api/payroll/statements").param("year", "2026").param("month", "7"))
                // BusinessException(503) → HTTP 503（providerUnavailable）
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        List<java.util.Map<String, Object>> rows = auditRowsAfter(before);
        assertEquals(1, rows.size(), "失敗時も1 request = 1 audit row");
        assertEquals("PAYROLL_SALARY_VIEW_202607", rows.get(0).get("application_code"));
        assertEquals(Boolean.FALSE, rows.get(0).get("success_flag"), "失敗はsuccess_flag=false");
        assertEquals(503, ((Number) rows.get(0).get("status")).intValue(), "provider障害statusを記録");
        String responseBody = failureResult.getResponse().getContentAsString();
        assertTrue(!responseBody.contains("250000") && !responseBody.contains("200000"),
                "APIエラーresponseに生金額を返さない（REV-001）: " + responseBody);

        // 2) link: BP拒否
        jdbcTemplate.update("UPDATE t_engineer SET employment_type = 'BP' WHERE id = 90001");
        before = auditMaxId();
        mockMvc.perform(put("/api/payroll/links/90001").param("employeeId", "501").with(csrf()))
                .andExpect(status().isBadRequest());
        rows = auditRowsAfter(before);
        assertEquals(1, rows.size(), "BP拒否時も1 row");
        assertEquals("PAYROLL_LINK", rows.get(0).get("application_code"));
        assertEquals(Boolean.FALSE, rows.get(0).get("success_flag"));
        jdbcTemplate.update("UPDATE t_engineer SET employment_type = '正社員' WHERE id = 90001");

        // 3) disconnect: revoke 5xx → JSON失敗（opaque 302成功扱いを廃止）
        before = auditMaxId();
        mockMvc.perform(delete("/integrations/freee").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        rows = auditRowsAfter(before);
        assertEquals(1, rows.size(), "解除失敗時も1 row");
        assertEquals("FREEE_DISCONNECT", rows.get(0).get("application_code"));
        assertEquals(Boolean.FALSE, rows.get(0).get("success_flag"));
        Long remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_freee_connection WHERE company_id = 123", Long.class);
        assertEquals(1L, remaining, "一時障害ではlocal rowを削除しない（R03-5）");
        server.verify();
    }

    @Test
    @WithMockUser(roles = "管理者", username = "audit-admin")
    @DisplayName("freee tokenErrorはHTTP 401を返さない（HFP-01-BUG-04）")
    void tokenErrorDoesNotReturnHttp401() throws Exception {
        seedLink();
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                requestTo(org.hamcrest.Matchers.startsWith(SALARY_URL)))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"access_denied\",\"code\":\"expired_access_token\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                requestTo(TOKEN_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"fixture-access-token-2\","
                        + "\"refresh_token\":\"fixture-refresh-token-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                requestTo(org.hamcrest.Matchers.startsWith(SALARY_URL)))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"access_denied\",\"code\":\"invalid_grant\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/payroll/statements").param("year", "2026").param("month", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("トークン")));
        server.verify();
    }

}
