package com.ses.controller.api;

import com.ses.dto.payroll.PayrollItemDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.security.BreakGlassService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T090 A2（本人給与/勤怠導線）のL2定向test。
 * 本人scope・再認証ゲート・no-store・一覧の金額非露出・未連携・provider障害をMockMvcで固定する。
 * 共有H2（application-test.yml）で動く。@Transactionalでロールバックし他テストと干渉しない。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MyPayrollApiControllerTest {

    /** 本人A（session主体）。freee従業員linkあり。 */
    private static final long USER_ID_A = 93001L;
    private static final long ENGINEER_ID_A = 93001L;
    /** 本人B（他人）。freee従業員linkあり。Aのsessionからは見えず、engineerId指定も無視される。 */
    private static final long USER_ID_B = 93002L;
    private static final long ENGINEER_ID_B = 93002L;
    /** 未紐付けユーザー（account linkなし）。 */
    private static final long USER_ID_C = 93003L;
    private static final long ENGINEER_ID_C = 93003L;
    /** freee未連携の要員（account linkあり・freee linkなし）。 */
    private static final long USER_ID_D = 93004L;
    private static final long ENGINEER_ID_D = 93004L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private FreeeIntegrationService freeeService;

    @BeforeEach
    void setUp() {
        // 本人A・他者B: account link + freee linkあり
        insertUser(USER_ID_A, ENGINEER_ID_A, "給与A", "secret-a");
        insertAccountLink(ENGINEER_ID_A, USER_ID_A);
        insertFreeeLink(ENGINEER_ID_A);
        insertUser(USER_ID_B, ENGINEER_ID_B, "給与B", "secret-b");
        insertAccountLink(ENGINEER_ID_B, USER_ID_B);
        insertFreeeLink(ENGINEER_ID_B);
        // C: account linkなし（未紐付け）。D: account linkあり・freee linkなし（未連携）。
        insertUser(USER_ID_C, ENGINEER_ID_C, "未紐付けC", "secret-c");
        insertUser(USER_ID_D, ENGINEER_ID_D, "未連携D", "secret-d");
        insertAccountLink(ENGINEER_ID_D, USER_ID_D);
        // 本人Aと他者Bの両方の明細をproviderが返しても、APIはAの分だけ返す（本人scope）。
        when(freeeService.statements(anyInt(), anyInt(), anyString())).thenReturn(List.of(
                statementOf(ENGINEER_ID_A, "給与A", "2026-08-25", "calculated"),
                statementOf(ENGINEER_ID_B, "給与B", "2026-08-25", "calculated")));
    }

    private RequestPostProcessor engineerUser(long userId) {
        return user(String.valueOf(userId)).roles("要員");
    }

    private void insertUser(long userId, long engineerId, String name, String password) {
        // 共有H2のsys_user.role ENUMはV1の4ロール（要員はV32のためH2 contextに無い）ため
        // DB行のroleは管理者を使う。認可は@WithMockUser相当のauthorities（roles=要員）が決める。
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password, real_name, role, email, status) "
                + "VALUES (?, ?, ?, ?, '管理者', ?, 1)",
                userId, "my-payroll-" + userId, password, name, userId + "@example.invalid");
        jdbcTemplate.update("INSERT INTO t_engineer (id, full_name, employment_type, status) "
                + "VALUES (?, ?, '正社員', '稼動中')", engineerId, name);
    }

    private void insertAccountLink(long engineerId, long userId) {
        jdbcTemplate.update("INSERT INTO t_engineer_account_link (engineer_id, sys_user_id) VALUES (?, ?)",
                engineerId, userId);
    }

    private void insertFreeeLink(long engineerId) {
        jdbcTemplate.update("INSERT INTO t_freee_employee_link (engineer_id, freee_employee_id, freee_company_id) "
                + "VALUES (?, ?, 1)", engineerId, "emp-" + engineerId);
    }

    private PayrollStatementDto statementOf(Long engineerId, String name, String payDate, String calcStatus) {
        PayrollStatementDto s = new PayrollStatementDto();
        s.setEngineerId(engineerId);
        s.setEngineerName(name);
        s.setEmployeeId("emp-" + engineerId);
        s.setEmployeeNumber("E-" + engineerId);
        s.setYear(2026);
        s.setMonth(8);
        s.setType("salary");
        s.setPayDate(payDate);
        s.setFixed(true);
        s.setCalculationStatus(calcStatus);
        s.setGrossAmount(new BigDecimal("300000"));
        s.setDeductionAmount(new BigDecimal("45000"));
        s.setNetAmount(new BigDecimal("255000"));
        s.setEmployerShareAmount(new BigDecimal("60000"));
        PayrollItemDto item = new PayrollItemDto();
        item.setCategory("PAYMENT");
        item.setName("基本給");
        item.setAmount(new BigDecimal("280000"));
        s.setItems(List.of(item));
        return s;
    }

    // ============================================================
    // 本人scope（R2.1 / design §3）
    // ============================================================

    @Test
    @DisplayName("一覧は本人の行だけを返し金額を含まない（他者IDパラメータは無視）")
    void 一覧は本人の行だけを返し金額を含まない() throws Exception {
        // engineerIdパラメータを送っても（APIは受け取らない）無視され、本人Aの行だけ返る。
        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(engineerUser(USER_ID_A))
                        .param("year", "2026").param("month", "8").param("type", "salary")
                        .param("engineerId", String.valueOf(ENGINEER_ID_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.linked").value(true))
                .andExpect(jsonPath("$.data.statements.length()").value(1))
                .andExpect(jsonPath("$.data.statements[0].month").value(8))
                .andExpect(jsonPath("$.data.statements[0].payDate").value("2026-08-25"))
                .andExpect(jsonPath("$.data.statements[0].calculationStatus").value("calculated"))
                // 一覧に金額フィールドを一切露出しない（R2.2）
                .andExpect(content().string(not(containsString("grossAmount"))))
                .andExpect(content().string(not(containsString("netAmount"))))
                .andExpect(content().string(not(containsString("deductionAmount"))))
                .andExpect(content().string(not(containsString("employerShareAmount"))))
                .andExpect(content().string(not(containsString("\"amount\""))));
    }

    @Test
    @DisplayName("一覧・詳細・再認証の全GETはCache-Control: no-store")
    void 全GETはnoStoreヘッダを付ける() throws Exception {
        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(engineerUser(USER_ID_A))
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"));

        MockHttpSession session = reauthSession();
        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_A)).session(session)
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    @DisplayName("本人Aのsessionから詳細を取得しても本人Aの明細だけ返る")
    void 詳細は本人の明細だけを返す() throws Exception {
        MockHttpSession session = reauthSession();
        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_A)).session(session)
                        .param("year", "2026").param("month", "8").param("type", "salary")
                        .param("engineerId", String.valueOf(ENGINEER_ID_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.engineerId").value(ENGINEER_ID_A))
                .andExpect(jsonPath("$.data.engineerName").value("給与A"));
    }

    @Test
    @DisplayName("他要員のstatementやnullデータが本人一覧および詳細に一切混入しない（S14-R2-P1-02）")
    void 他要員のstatementやnullデータは厳格に除外される() throws Exception {
        // freeeService が複数人分（本人A、他者B、他者C、engineerIdがnullの行）を返却した場合
        PayrollStatementDto nullIdStatement = statementOf(null, "不明", "2026-08-25", "calculated");
        nullIdStatement.setEngineerId(null);
        PayrollStatementDto otherStatementC = statementOf(99999L, "他要員C", "2026-08-25", "calculated");
        when(freeeService.statements(anyInt(), anyInt(), anyString())).thenReturn(List.of(
                otherStatementC,
                nullIdStatement,
                statementOf(ENGINEER_ID_A, "給与A", "2026-08-25", "calculated"),
                statementOf(ENGINEER_ID_B, "給与B", "2026-08-25", "calculated")
        ));

        // 本人Aの一覧には本人Aの1件のみ返る
        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(engineerUser(USER_ID_A))
                        .param("year", "2026").param("month", "8").param("type", "salary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.statements.length()").value(1))
                .andExpect(jsonPath("$.data.statements[0].payDate").value("2026-08-25"));

        // 本人Aの詳細には本人Aの明細のみ返る
        MockHttpSession session = reauthSession();
        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_A)).session(session)
                        .param("year", "2026").param("month", "8").param("type", "salary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.engineerId").value(ENGINEER_ID_A))
                .andExpect(jsonPath("$.data.engineerName").value("給与A"));
    }

    @Test
    @DisplayName("controllerソースにengineerIdパラメータが存在しない（静的assert / design §3）")
    void controllerはengineerIdパラメータを受け取らない() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "com", "ses",
                "controller", "api", "MyPayrollApiController.java"));
        assertFalse(source.matches("(?s).*@(RequestParam|PathVariable)[^\\n]*[Ee]ngineerId.*"),
                "design §3: リクエストにengineerIdパラメータを一切受け取らないこと");
        assertFalse(source.contains("?engineerId="), "design §3: engineerIdクエリを組み立てないこと");
    }

    @Test
    @DisplayName("未紐付けアカウントは403（error.my.notLinked）")
    void 未紐付けは403() throws Exception {
        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(engineerUser(USER_ID_C))
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/my/payroll/reauthenticate")
                        .with(engineerUser(USER_ID_C)).with(csrf())
                        .contentType("application/json").content("{\"password\":\"secret-c\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("営業ロールは本人給与APIへ到達できない")
    void 営業は本人給与APIへ到達できない() throws Exception {
        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(user("93000").roles("営業"))
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // 再認証ゲート（R2.2）
    // ============================================================

    @Test
    @DisplayName("詳細は再認証なし403 → 正しいパスワードで再認証 → 金額付きで200")
    void 再認証フローが一気通貫で動く() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_A)).session(session)
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/my/payroll/reauthenticate")
                        .with(engineerUser(USER_ID_A)).session(session).with(csrf())
                        .contentType("application/json").content("{\"password\":\"wrong-pass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/my/payroll/reauthenticate")
                        .with(engineerUser(USER_ID_A)).session(session).with(csrf())
                        .contentType("application/json").content("{\"password\":\"secret-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.expiresInMinutes").value(10));

        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_A)).session(session)
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.grossAmount").value(300000))
                .andExpect(jsonPath("$.data.deductionAmount").value(45000))
                .andExpect(jsonPath("$.data.netAmount").value(255000))
                .andExpect(jsonPath("$.data.employerShareAmount").value(60000))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("基本給"));
    }

    @Test
    @DisplayName("再認証から10分経過後の詳細は403")
    void 再認証の期限切れは403() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(MyPayrollApiController.SESSION_REAUTH_AT,
                System.currentTimeMillis() - 11L * 60 * 1000);
        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_A)).session(session)
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("break-glass sessionでは再認証済みでも詳細は403")
    void breakGlassSessionは詳細を拒否する() throws Exception {
        MockHttpSession session = reauthSession();
        session.setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_A)).session(session)
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    // ============================================================
    // 未連携・provider障害
    // ============================================================

    @Test
    @DisplayName("freee未連携の要員は「未連携」を返し0円の明細を作らない")
    void freee未連携は未連携として返す() throws Exception {
        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(engineerUser(USER_ID_D))
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.linked").value(false))
                .andExpect(jsonPath("$.data.statements.length()").value(0))
                .andExpect(content().string(not(containsString("\"amount\""))))
                .andExpect(content().string(not(containsString("grossAmount"))));

        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_D)).session(reauthSession())
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("provider障害時は503系エラーで画面を壊さない")
    void provider障害は503系エラーを返す() throws Exception {
        when(freeeService.statements(anyInt(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("provider down"));

        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(engineerUser(USER_ID_A))
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_A)).session(reauthSession())
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    @DisplayName("対象月に本人の明細が無い場合は詳細404")
    void 本人の明細が無い月は詳細404() throws Exception {
        when(freeeService.statements(anyInt(), anyInt(), anyString()))
                .thenReturn(List.of(statementOf(ENGINEER_ID_B, "給与B", "2026-08-25", "calculated")));

        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(USER_ID_A)).session(reauthSession())
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("不正な年月・typeは400")
    void 不正な年月typeは400() throws Exception {
        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(engineerUser(USER_ID_A))
                        .param("year", "1999").param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(engineerUser(USER_ID_A))
                        .param("year", "2026").param("month", "8").param("type", "overtime"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ============================================================
    // ページ導線（R2.3）
    // ============================================================

    @Test
    @DisplayName("要員は/my/payrollページを表示でき、営業は403")
    void 給与ページは要員のみ表示できる() throws Exception {
        mockMvc.perform(get("/my/payroll").with(engineerUser(USER_ID_A)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("my-payroll.js")));

        mockMvc.perform(get("/my/payroll").with(user("93000").roles("営業")))
                .andExpect(status().isForbidden());
    }

    /** 再認証済みsession（10分以内）を作る。 */
    private MockHttpSession reauthSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(MyPayrollApiController.SESSION_REAUTH_AT, System.currentTimeMillis());
        return session;
    }
}
