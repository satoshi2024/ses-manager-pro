package com.ses.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.payroll.PayrollItemDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSales;
import com.ses.entity.Project;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.SystemConfigService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.changerequest.EngineerChangeRequestService;
import com.ses.service.expense.ExpenseAccountingJobScheduler;
import com.ses.service.expense.ExpenseRequestService;
import com.ses.service.oneonone.OneOnOneRequestService;
import com.ses.service.survey.SurveyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * T093 M 回帰総合テスト。
 * 要員セルフサービスポータルV2（S14）の全機能一気通貫動作、本人A/BのPII非漏洩・IDOR遮断、
 * 既存マイ勤怠導線回帰、各画面のレンダリング・権限分離を網羅的に検証する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EngineerSelfServicePortalMRegressionTest {

    private static final AtomicInteger ROUTE_SEQ = new AtomicInteger(5000);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private EngineerAccountLinkMapper accountLinkMapper;
    @Autowired
    private EngineerSalesMapper engineerSalesMapper;
    @Autowired
    private UserOrganizationMapper userOrganizationMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private ContractMapper contractMapper;
    @Autowired
    private ApprovalRouteMapper approvalRouteMapper;
    @Autowired
    private ApprovalRouteStepMapper approvalRouteStepMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private EngineerChangeRequestService changeRequestService;
    @Autowired
    private ExpenseRequestService expenseRequestService;
    @Autowired
    private OneOnOneRequestService oneOnOneService;
    @Autowired
    private SurveyService surveyService;
    @Autowired
    private com.ses.service.NotificationService notificationService;
    @Autowired
    private ApprovalEngineService approvalEngineService;
    @Autowired
    private ExpenseAccountingJobScheduler expenseScheduler;
    @Autowired
    private SystemConfigService systemConfigService;

    @MockBean
    private FreeeIntegrationService freeeService;

    private Long userIdA;
    private Long engineerIdA;
    private Long userIdB;
    private Long engineerIdB;
    private Long adminUserId;
    private Long hrUserId;
    private Long salesUserId;
    private Long managerUserId;
    private Long orgId;

    @BeforeEach
    void setUp() {
        systemConfigService.put("survey.min-answers", "1", "テスト用閾値");

        adminUserId = insertUser("管理者", "admin");
        hrUserId = insertUser("HR", "hr");
        salesUserId = insertUser("営業", "sales");
        managerUserId = insertUser("マネージャー", "manager");

        orgId = createOrg();
        assignManager(managerUserId, orgId);

        userIdA = insertUser("要員", "engineerA");
        engineerIdA = createEngineer("要員A-太郎", orgId);
        link(engineerIdA, userIdA);
        assignSales(engineerIdA, salesUserId);
        insertFreeeLink(engineerIdA);

        userIdB = insertUser("要員", "engineerB");
        engineerIdB = createEngineer("要員B-次郎", orgId);
        link(engineerIdB, userIdB);
        assignSales(engineerIdB, salesUserId);
        insertFreeeLink(engineerIdB);

        insertRoute("profile.change", List.of(List.of(hrUserId)));
        insertRoute("skill.change", List.of(List.of(hrUserId)));
        insertRoute("expense.request", List.of(List.of(adminUserId)));

        when(freeeService.statementForEngineer(anyLong(), anyInt(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    Long engId = inv.getArgument(0);
                    if (Long.valueOf(engineerIdA).equals(engId)) {
                        return statementOf(engineerIdA, "要員A-太郎", "2026-08-25", "calculated");
                    }
                    if (Long.valueOf(engineerIdB).equals(engId)) {
                        return statementOf(engineerIdB, "要員B-次郎", "2026-08-25", "calculated");
                    }
                    return null;
                });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private RequestPostProcessor engineerUser(long userId) {
        return user(String.valueOf(userId)).roles("要員");
    }

    private void authenticateAs(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(userId), "N/A", List.of(new SimpleGrantedAuthority("ROLE_" + role))
                )
        );
    }

    // ============================================================
    // 1. PII Leak Scan & IDOR 防御テスト (User A vs User B)
    // ============================================================

    @Test
    @DisplayName("PII Leak Scan: 本人Aは本人Bのプロフィール・変更申請・経費・1on1・給与・サーベイを一切取得できず漏洩しない")
    void piiLeakScanAndIdorProtection() throws Exception {
        // --- 1.1 プロフィール & スキルシート ---
        mockMvc.perform(get("/api/my/profile")
                        .with(engineerUser(userIdA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fullName").value("要員A-太郎"))
                .andExpect(content().string(not(containsString("要員B-次郎"))))
                .andExpect(content().string(not(containsString("costPrice"))))
                .andExpect(content().string(not(containsString("sellingPrice"))))
                .andExpect(content().string(not(containsString("commission"))));

        // --- 1.2 変更申請 (Change Request) ---
        // Aが下書き作成
        EngineerChangeRequestService.ChangeRequestDto draftA = changeRequestService.createDraft(engineerIdA,
                "profile.change", Map.of("nearestStation", "A駅", "experienceYears", 5));
        // Bが下書き作成
        EngineerChangeRequestService.ChangeRequestDto draftB = changeRequestService.createDraft(engineerIdB,
                "profile.change", Map.of("nearestStation", "B駅", "experienceYears", 3));

        // Aの一覧にはAの申請のみ (Bの申請は含まれない)
        mockMvc.perform(get("/api/my/change-requests")
                        .with(engineerUser(userIdA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(draftA.id()))
                .andExpect(content().string(not(containsString("B駅"))));

        // AがBの申請IDを指定して詳細取得 -> 404 (oracle prevention: error.changeRequest.notFound)
        mockMvc.perform(get("/api/my/change-requests/" + draftB.id())
                        .with(engineerUser(userIdA)))
                .andExpect(status().isNotFound());

        // AがBの申請IDを指定してsubmit -> 404
        mockMvc.perform(post("/api/my/change-requests/" + draftB.id() + "/submit")
                        .with(engineerUser(userIdA)).with(csrf()))
                .andExpect(status().isNotFound());

        // AがBの申請IDを指定してwithdraw -> 404
        mockMvc.perform(post("/api/my/change-requests/" + draftB.id() + "/withdraw")
                        .with(engineerUser(userIdA)).with(csrf()))
                .andExpect(status().isNotFound());

        // --- 1.3 経費申請 (Expense) & 領収書 ---
        authenticateAs(userIdA, "要員");
        ExpenseRequestService.ExpenseRequestDto expenseA = expenseRequestService.createDraft(engineerIdA,
                new ExpenseRequestService.ExpenseDraftCommand(LocalDate.of(2026, 8, 1), "交通費",
                        new BigDecimal("1500"), null, null, "Aの電車代"));
        expenseRequestService.attachReceipt(engineerIdA, expenseA.id(), "receiptA.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF-1.4 receipt A".getBytes(StandardCharsets.UTF_8)));

        authenticateAs(userIdB, "要員");
        ExpenseRequestService.ExpenseRequestDto expenseB = expenseRequestService.createDraft(engineerIdB,
                new ExpenseRequestService.ExpenseDraftCommand(LocalDate.of(2026, 8, 2), "立替経費",
                        new BigDecimal("4200"), null, null, "Bの書籍代"));
        expenseRequestService.attachReceipt(engineerIdB, expenseB.id(), "receiptB.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF-1.4 receipt B".getBytes(StandardCharsets.UTF_8)));

        // Aの一覧にはAの経費のみ
        mockMvc.perform(get("/api/my/expenses")
                        .with(engineerUser(userIdA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(expenseA.id()))
                .andExpect(content().string(not(containsString("Bの書籍代"))));

        // AがBの経費を削除試行 -> 404 (oracle prevention: error.expense.notFound)
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/my/expenses/" + expenseB.id())
                        .with(engineerUser(userIdA)).with(csrf()))
                .andExpect(status().isNotFound());

        // AがBの経費を更新試行 -> 404
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/my/expenses/" + expenseB.id())
                        .with(engineerUser(userIdA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expenseDate\":\"2026-08-05\",\"category\":\"交通費\",\"amount\":500}"))
                .andExpect(status().isNotFound());

        // AがBの領収書をダウンロード -> 404
        mockMvc.perform(get("/api/my/expenses/" + expenseB.id() + "/receipt")
                        .with(engineerUser(userIdA)))
                .andExpect(status().isNotFound());

        // AがBの経費をsubmit -> 404
        mockMvc.perform(post("/api/my/expenses/" + expenseB.id() + "/submit")
                        .with(engineerUser(userIdA)).with(csrf()))
                .andExpect(status().isNotFound());

        // --- 1.4 1on1 & Confidential Memo ---
        authenticateAs(userIdA, "要員");
        OneOnOneRequestService.OneOnOneDto oneOnOneA = oneOnOneService.create(engineerIdA, salesUserId,
                List.of(LocalDate.now().plusDays(3)));
        authenticateAs(userIdB, "要員");
        OneOnOneRequestService.OneOnOneDto oneOnOneB = oneOnOneService.create(engineerIdB, salesUserId,
                List.of(LocalDate.now().plusDays(4)));

        // Aの一覧にはAの1on1のみ
        mockMvc.perform(get("/api/my/one-on-ones")
                        .with(engineerUser(userIdA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(oneOnOneA.id()));

        // AがBの1on1詳細を取得 -> 404 (oracle prevention: error.oneOnOne.notFound)
        mockMvc.perform(get("/api/my/one-on-ones/" + oneOnOneB.id())
                        .with(engineerUser(userIdA)))
                .andExpect(status().isNotFound());

        // AがBの1on1をcancel -> 404
        mockMvc.perform(post("/api/my/one-on-ones/" + oneOnOneB.id() + "/cancel")
                        .with(engineerUser(userIdA)).with(csrf()))
                .andExpect(status().isNotFound());

        // HRがAの1on1にconfidential memoを登録
        authenticateAs(hrUserId, "HR");
        oneOnOneService.savePrivateNote(oneOnOneA.id(), "要員Aの極秘健康相談メモ（HR限定）");
        // Aが詳細を見てもconfidential memoは入らない
        mockMvc.perform(get("/api/my/one-on-ones/" + oneOnOneA.id())
                        .with(engineerUser(userIdA)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("極秘健康相談メモ"))))
                .andExpect(jsonPath("$.data.privateNoteRef").doesNotExist());

        // 営業が管理詳細を見てもconfidential memoは入らない
        mockMvc.perform(get("/api/one-on-ones/" + oneOnOneA.id())
                        .with(user(String.valueOf(salesUserId)).roles("営業")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("極秘健康相談メモ"))))
                .andExpect(jsonPath("$.data.privateNoteRef").doesNotExist());

        // --- 1.5 給与明細 (Payroll) ---
        // 一覧取得: 金額露出なし、Aの分のみ
        mockMvc.perform(get("/api/my/payroll/statements")
                        .with(engineerUser(userIdA))
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.data.statements.length()").value(1))
                .andExpect(content().string(not(containsString("grossAmount"))))
                .andExpect(content().string(not(containsString("netAmount"))));

        // 再認証前詳細: 403
        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(userIdA))
                        .param("year", "2026").param("month", "8"))
                .andExpect(status().isForbidden());

        // 再認証後詳細: Aの明細のみ、Bの明細やデータは混入しない
        MockHttpSession sessionA = new MockHttpSession();
        mockMvc.perform(post("/api/my/payroll/reauthenticate")
                        .with(engineerUser(userIdA)).session(sessionA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"engineerA\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/my/payroll/statement")
                        .with(engineerUser(userIdA)).session(sessionA)
                        .param("year", "2026").param("month", "8")
                        .param("engineerId", String.valueOf(engineerIdB)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.data.engineerId").value(engineerIdA))
                .andExpect(jsonPath("$.data.engineerName").value("要員A-太郎"))
                .andExpect(jsonPath("$.data.netAmount").value(255000));
    }

    // ============================================================
    // 2. ページナビゲーション & モバイル390px & ロール認可
    // ============================================================

    @Test
    @DisplayName("全Myポータル画面のナビゲーション (200 OK) と各ロールの認可分離")
    void pageNavigationAndRoleBoundaries() throws Exception {
        // 要員ロール: 全Myページにアクセス可能
        List<String> myPages = List.of(
                "/my/dashboard",
                "/my/profile",
                "/my/timesheet",
                "/my/expenses",
                "/my/one-on-ones",
                "/my/surveys",
                "/my/payroll"
        );
        for (String path : myPages) {
            mockMvc.perform(get(path).with(engineerUser(userIdA)))
                    .andExpect(status().isOk());
        }

        // 要員ロール: 管理画面へのアクセスは403
        List<String> managementPages = List.of(
                "/engineer-change-requests",
                "/expenses",
                "/one-on-ones",
                "/surveys"
        );
        for (String path : managementPages) {
            mockMvc.perform(get(path).with(engineerUser(userIdA)))
                    .andExpect(status().isForbidden());
        }

        // 管理者: 管理画面へアクセス可能
        for (String path : managementPages) {
            mockMvc.perform(get(path).with(user(String.valueOf(adminUserId)).roles("管理者")))
                    .andExpect(status().isOk());
        }

        // 営業: 1on1管理画面へアクセス可能、経費・変更申請・サーベイ管理は403
        mockMvc.perform(get("/one-on-ones").with(user(String.valueOf(salesUserId)).roles("営業")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/expenses").with(user(String.valueOf(salesUserId)).roles("営業")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/engineer-change-requests").with(user(String.valueOf(salesUserId)).roles("営業")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/surveys").with(user(String.valueOf(salesUserId)).roles("営業")))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // 3. 既存マイ勤怠 (/my/timesheet) 回帰テスト
    // ============================================================

    @Test
    @DisplayName("既存マイ勤怠導線回帰: 要員Aの日次登録・月次集計・他要員への越権防止")
    void myTimesheetRegressionAndIsolation() throws Exception {
        Customer customer = new Customer();
        customer.setCompanyName("テスト顧客-" + System.nanoTime());
        customer.setContactEmail("test" + System.nanoTime() + "@example.com");
        customerMapper.insert(customer);

        Project project = new Project();
        project.setCustomerId(customer.getId());
        project.setProjectName("テスト案件-" + System.nanoTime());
        project.setStatus("募集中");
        project.setStartDate(LocalDate.of(2026, 8, 1));
        project.setEndDate(LocalDate.of(2026, 8, 31));
        projectMapper.insert(project);

        Contract contractA = new Contract();
        contractA.setContractNo("CT-A-" + System.nanoTime());
        contractA.setCustomerId(customer.getId());
        contractA.setProjectId(project.getId());
        contractA.setEngineerId(engineerIdA);
        contractA.setContractType("準委任");
        contractA.setStatus("稼動中");
        contractA.setStartDate(LocalDate.of(2026, 8, 1));
        contractA.setEndDate(LocalDate.of(2026, 8, 31));
        contractA.setSellingPrice(new BigDecimal("700000"));
        contractA.setCostPrice(new BigDecimal("500000"));
        contractMapper.insert(contractA);

        Contract contractB = new Contract();
        contractB.setContractNo("CT-B-" + System.nanoTime());
        contractB.setCustomerId(customer.getId());
        contractB.setProjectId(project.getId());
        contractB.setEngineerId(engineerIdB);
        contractB.setContractType("準委任");
        contractB.setStatus("稼動中");
        contractB.setStartDate(LocalDate.of(2026, 8, 1));
        contractB.setEndDate(LocalDate.of(2026, 8, 31));
        contractB.setSellingPrice(new BigDecimal("700000"));
        contractB.setCostPrice(new BigDecimal("500000"));
        contractMapper.insert(contractB);

        // Aが日次勤怠を登録
        mockMvc.perform(post("/api/my/timesheet/daily")
                        .with(engineerUser(userIdA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractId": %d,
                                  "workMonth": "2026-08",
                                  "workDate": "2026-08-03",
                                  "startTime": "09:00",
                                  "endTime": "18:00",
                                  "breakMinutes": 60,
                                  "remarks": "通常勤務"
                                }
                                """.formatted(contractA.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // Aが自分の月次勤怠を取得
        mockMvc.perform(get("/api/my/timesheet")
                        .with(engineerUser(userIdA))
                        .param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.engineerName").value("要員A-太郎"))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].contractId").value(contractA.getId()));

        // BがAの契約に対して日次勤怠を登録しようとする -> 403
        mockMvc.perform(post("/api/my/timesheet/daily")
                        .with(engineerUser(userIdB)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractId": %d,
                                  "workMonth": "2026-08",
                                  "workDate": "2026-08-03",
                                  "startTime": "09:00",
                                  "endTime": "18:00",
                                  "breakMinutes": 60,
                                  "remarks": "越権登録試行"
                                }
                                """.formatted(contractA.getId())))
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // 4. 一気通貫ライフサイクル結合テスト
    // ============================================================

    @Test
    @DisplayName("一気通貫ライフサイクル: プロフィール変更申請・経費会計連携・1on1・サーベイ回答が正しく完結する")
    void fullLifecycleSynergyIntegration() throws Exception {
        // --- 4.1 プロフィール変更申請ライフサイクル ---
        authenticateAs(userIdA, "要員");
        EngineerChangeRequestService.ChangeRequestDto draft = changeRequestService.createDraft(engineerIdA,
                "profile.change", Map.of("nearestStation", "渋谷駅", "experienceYears", 7));
        EngineerChangeRequestService.ChangeRequestDto submitted = changeRequestService.submit(engineerIdA, draft.id());
        assertEquals("申請中", submitted.status());

        // HR承認 -> 反映
        authenticateAs(hrUserId, "HR");
        approvalEngineService.approve(submitted.approvalRequestId(), hrUserId, "承認");
        Engineer masterA = engineerMapper.selectById(engineerIdA);
        assertEquals("渋谷駅", masterA.getNearestStation());
        assertEquals(7, masterA.getExperienceYears());

        // スキルシート確認日更新
        authenticateAs(userIdA, "要員");
        EngineerChangeRequestService.SkillSheetPreview preview = changeRequestService.skillSheetPreview(engineerIdA);
        changeRequestService.confirmSkillSheet(engineerIdA, preview.fingerprint());
        EngineerChangeRequestService.SkillSheetPreview previewAfterConfirm = changeRequestService.skillSheetPreview(engineerIdA);
        assertNotNull(previewAfterConfirm.confirmedAt());

        // --- 4.2 経費申請ライフサイクル & 会計連携 ---
        ExpenseRequestService.ExpenseRequestDto expense = expenseRequestService.createDraft(engineerIdA,
                new ExpenseRequestService.ExpenseDraftCommand(LocalDate.of(2026, 8, 10), "交通費",
                        new BigDecimal("2300"), null, null, "客先訪問"));
        expenseRequestService.attachReceipt(engineerIdA, expense.id(), "receipt.pdf", "application/pdf",
                new ByteArrayInputStream("%PDF-1.4 test".getBytes(StandardCharsets.UTF_8)));
        ExpenseRequestService.ExpenseRequestDto expenseSubmitted = expenseRequestService.submit(engineerIdA, expense.id());

        // 管理者承認
        authenticateAs(adminUserId, "管理者");
        approvalEngineService.approve(expenseSubmitted.approvalRequestId(), adminUserId, "経費承認");
        // Scheduler実行 -> 会計連携済
        expenseScheduler.processDue(100);
        ExpenseRequestService.ExpenseRequestDto expenseSent = expenseRequestService.detailManagement(expense.id());
        assertEquals("会計連携済", expenseSent.status());

        // 支払済マーク
        ExpenseRequestService.ExpenseRequestDto expensePaid = expenseRequestService.markPaid(expense.id());
        assertEquals("支払済", expensePaid.status());

        // --- 4.3 サーベイ回答 & 匿名集計ライフサイクル ---
        authenticateAs(hrUserId, "HR");
        SurveyService.TemplateDto template = surveyService.createTemplate(
                "q-m-" + System.nanoTime(), "月次稼動アンケート", "説明",
                List.of(new SurveyService.QuestionDef("q1", "稼働状況", "SCALE1_5", false),
                        new SurveyService.QuestionDef("q2", "相談事項", "COMMENT", true))
        );
        SurveyService.CampaignDto campaign = surveyService.createCampaign(template.id(), "2026-08期", null, null);
        surveyService.activateCampaign(campaign.id());

        // 要員A回答
        authenticateAs(userIdA, "要員");
        surveyService.submitAnswers(engineerIdA, campaign.id(), true, List.of(
                new SurveyService.AnswerInput("q1", 5, null, "PUBLIC"),
                new SurveyService.AnswerInput("q2", null, "特に懸念なし", "CONFIDENTIAL")
        ));

        // HR集計: 1件の回答が集計されている
        authenticateAs(hrUserId, "HR");
        SurveyService.AggregateResult hrAgg = surveyService.aggregate(campaign.id());
        assertEquals(1, hrAgg.questions().get(0).answeredCount());
        assertEquals(new BigDecimal("5.00"), hrAgg.questions().get(0).average());
    }

    @Test
    @DisplayName("freee給与明細取得で単一要員分のみ取得され他要員のデータは混入しない")
    void freee給与明細取得で単一要員分のみ取得され他要員のデータは混入しない() {
        Long userId = insertUser("要員", "freee-test");
        Long engineerId = createEngineer("Freee要員", null);
        link(engineerId, userId);
        insertFreeeLink(engineerId);

        when(freeeService.statementForEngineer(eq(engineerId), eq(2026), eq(8), eq("salary")))
                .thenReturn(statementOf(engineerId, "Freee要員", "2026-08-25", "CONFIRMED"));

        PayrollStatementDto dto = freeeService.statementForEngineer(engineerId, 2026, 8, "salary");
        assertNotNull(dto);
        assertEquals(engineerId, dto.getEngineerId());
        assertEquals("Freee要員", dto.getEngineerName());
    }

    @Test
    @DisplayName("要員ポータル通知がcanonicalキーで通知一覧および未読カウントへ集計される")
    void 要員ポータル通知がcanonicalキーで通知一覧および未読カウントへ集計される() {
        Long userId = insertUser("要員", "notif-test");
        Long engineerId = createEngineer("Notif要員", null);
        link(engineerId, userId);

        // menuKey省略overloadで発行し、resolverがcanonicalキー（myProfile/myExpenses）へ解決することを検証
        notificationService.publishToUser(userId, "CHANGE_REQUEST_APPLIED", "変更申請反映",
                "[\"notification.msg.CHANGE_REQUEST_APPLIED\", \"プロフィール\"]", "/my/profile",
                "cr-notif-" + System.nanoTime());
        notificationService.publishToUser(userId, "EXPENSE_ACCOUNTING_SENT", "経費連携",
                "[\"notification.msg.EXPENSE_ACCOUNTING_SENT\", \"2300\"]", "/my/expenses",
                "exp-notif-" + System.nanoTime());

        long unread = notificationService.unreadCount(userId);
        assertTrue(unread >= 2);

        var page = notificationService.pageForUser(userId, 1L, 10L, null, null);
        assertNotNull(page);
        assertTrue(page.getRecords().stream().anyMatch(n -> "CHANGE_REQUEST_APPLIED".equals(n.getType())));
        assertTrue(page.getRecords().stream().anyMatch(n -> "EXPENSE_ACCOUNTING_SENT".equals(n.getType())));

        var rows = notificationMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.Notification>()
                .eq(com.ses.entity.Notification::getRecipientUserId, userId));
        assertTrue(rows.stream().anyMatch(n -> "CHANGE_REQUEST_APPLIED".equals(n.getType()) && "myProfile".equals(n.getMenuKey())));
        assertTrue(rows.stream().anyMatch(n -> "EXPENSE_ACCOUNTING_SENT".equals(n.getType()) && "myExpenses".equals(n.getMenuKey())));
    }

    // ------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------

    private Long insertUser(String role, String name) {
        SysUser user = SysUser.builder()
                .username("u-" + name + "-" + System.nanoTime())
                .password(name)
                .realName(name)
                .role("要員".equals(role) ? "管理者" : role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private Long createEngineer(String fullName, Long organizationId) {
        Engineer engineer = Engineer.builder()
                .fullName(fullName)
                .fullNameKana("テスト")
                .employmentType("正社員")
                .status("稼動中")
                .nearestStation("新宿駅")
                .organizationId(organizationId)
                .experienceYears(3)
                .build();
        engineerMapper.insert(engineer);
        jdbcTemplate.update("DELETE FROM t_engineer_accounting_history WHERE engineer_id = ?", engineer.getId());
        return engineer.getId();
    }

    private void link(Long engineerId, Long sysUserId) {
        accountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getEngineerId, engineerId));
        accountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, sysUserId));
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(sysUserId);
        accountLinkMapper.insert(link);
    }

    private void assignSales(Long engineerId, Long salesUserId) {
        EngineerSales sales = EngineerSales.builder()
                .engineerId(engineerId)
                .salesUserId(salesUserId)
                .primaryFlag(1)
                .assignedAt(LocalDate.now())
                .build();
        engineerSalesMapper.insert(sales);
    }

    private void insertFreeeLink(Long engineerId) {
        jdbcTemplate.update("DELETE FROM t_freee_employee_link WHERE engineer_id = ?", engineerId);
        jdbcTemplate.update("INSERT INTO t_freee_employee_link (engineer_id, freee_employee_id, freee_company_id) "
                + "VALUES (?, ?, 1)", engineerId, "emp-" + engineerId);
    }

    private Long createOrg() {
        String code = "M-ORG-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70003, ?, ?, '部門', '2026-01-01', '有効')", code, "M回帰組織-" + System.nanoTime());
        return jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
    }

    private void assignManager(Long managerUserId, Long organizationId) {
        UserOrganization row = new UserOrganization();
        row.setUserId(managerUserId);
        row.setOrganizationId(organizationId);
        row.setPrimaryFlag(1);
        row.setValidFrom(LocalDate.of(2026, 1, 1));
        row.setValidTo(null);
        userOrganizationMapper.insert(row);
    }

    private void insertRoute(String requestType, List<List<Long>> steps) {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L)
                .requestType(requestType)
                .organizationId(null)
                .minAmount(null)
                .maxAmount(null)
                .versionNo(ROUTE_SEQ.incrementAndGet())
                .validFrom(LocalDate.now().minusDays(1))
                .activeFlag(1)
                .build();
        approvalRouteMapper.insert(route);
        for (int i = 0; i < steps.size(); i++) {
            int stepNo = i + 1;
            for (Long approverId : steps.get(i)) {
                ApprovalRouteStep step = ApprovalRouteStep.builder()
                        .routeId(route.getId())
                        .stepNo(stepNo)
                        .parallelGroup(stepNo)
                        .approverType("USER")
                        .approverValue(String.valueOf(approverId))
                        .build();
                approvalRouteStepMapper.insert(step);
            }
        }
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
}
