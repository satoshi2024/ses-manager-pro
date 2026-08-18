package com.ses.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.ExternalMapping;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.UserOrganization;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.InvoicePaymentMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 Stage B 会計・支払連携 Browser Demo (R4-P1-01 / tasks §R4-T08).
 * 実 Chrome (CDP・headless) で Desktop (1920x1080) と Mobile (390x844) の実測を行い、
 * DOM 検証・スクリーンショット・サマリーを evidence ディレクトリへ保存する。
 *
 * <p>実演・検証内容:
 * <ol>
 *   <li>管理者: ジョブ/マッピング/接続/プレビュー/月次照合 (4母集団 MATCHED・締可 badge)</li>
 *   <li>401 トークン失効からの自動復旧 (freee stub が初回 401 -> OAuth 更新 -> リプレイ成功)</li>
 *   <li>マネージャー境界: 全社共通 (organization_id NULL) ジョブは不可視、自組織ジョブのみ可視</li>
 *   <li>desktop / 390px の両ビューポートで console error 0</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountingIntegrationBrowserDemoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** freee 連携先をローカル stub に差し替える (401 復旧 + 4母集団 deal 応答)。 */
    private static com.sun.net.httpserver.HttpServer stubServer;
    private static volatile String dealsJson = "{\"deals\": []}";
    private static final AtomicInteger deals401Count = new AtomicInteger(0);
    private static final AtomicInteger tokenRefreshCount = new AtomicInteger(0);

    static {
        try {
            stubServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
            stubServer.createContext("/api/1/deals", exchange -> {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth == null || auth.contains("demo-access-token")) {
                    deals401Count.incrementAndGet();
                    byte[] body = "{\"errors\":[{\"messages\":[\"token expired\"]}]}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(401, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    return;
                }
                byte[] body = dealsJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            stubServer.createContext("/oauth/token", exchange -> {
                tokenRefreshCount.incrementAndGet();
                byte[] body = "{\"access_token\":\"demo-access-v2\",\"refresh_token\":\"demo-refresh-v2\",\"expires_in\":3600}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            stubServer.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("freee stub server failed to start", e);
        }
    }

    @DynamicPropertySource
    static void freeeStub(DynamicPropertyRegistry registry) {
        int port = stubServer.getAddress().getPort();
        registry.add("freee.api.base-url", () -> "http://localhost:" + port);
        registry.add("freee.oauth.token-url", () -> "http://localhost:" + port + "/oauth/token");
    }

    @AfterAll
    static void stopStub() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private IntegrationConnectionService connectionService;

    @Autowired
    private ExternalMappingService mappingService;

    @Autowired
    private IntegrationJobService jobService;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @Autowired
    private ExpenseRequestMapper expenseRequestMapper;

    @Autowired
    private InvoicePaymentMapper invoicePaymentMapper;

    @Autowired
    private WorkRecordMapper workRecordMapper;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    @Autowired
    private com.ses.mapper.CustomerMapper customerMapper;

    @Autowired
    private com.ses.mapper.EngineerMapper engineerMapper;

    private Long managerOrgId;
    private Long managerJobId;
    private Long adminJobId;

    @Test
    @DisplayName("S15 Browser Demo: 会計連携画面（ジョブ/マッピング/接続/プレビュー/月次照合・4母集団・401復旧・マネージャー境界）実測")
    void captureAccountingScreensWithRealBrowser() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");

        // ===== シードデータ準備 =====
        String month = YearMonth.now().toString();
        IntegrationConnection conn = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        IntegrationTokensDto tokens = IntegrationTokensDto.builder()
                .accessToken("demo-access-token")
                .refreshToken("demo-refresh-token")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(conn.getId(), tokens, 99001L, "テスト会計事業所", 1L);

        ExternalMapping mapping = new ExternalMapping();
        mapping.setConnectionId(conn.getId());
        mapping.setObjectType("CUSTOMER_PARTNER");
        mapping.setInternalCode("CUST-101");
        mapping.setExternalId("9001");
        mapping.setExternalCode("テスト顧客株式会社");
        mappingService.saveOrUpdateMapping(mapping);

        // 全社共通ジョブ (organization_id NULL = 管理者のみ可視)
        IntegrationJob job1 = jobService.createJob(
                conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", 101L, "INV-DEMO-001", "hash-demo-101");
        jobService.claimJob(job1.getId());
        jobService.markSucceeded(job1.getId(), "DEAL-99001", "req-demo-01", "freee売上連携完了");
        adminJobId = job1.getId();

        // ===== 4母集団 (当月) =====
        LocalDate today = LocalDate.now();
        com.ses.entity.Customer demoCustomer = new com.ses.entity.Customer();
        demoCustomer.setCompanyName("デモ顧客株式会社");
        customerMapper.insert(demoCustomer);

        com.ses.entity.Engineer demoEngineer = new com.ses.entity.Engineer();
        demoEngineer.setFullName("デモ要員");
        demoEngineer.setEmploymentType("正社員");
        engineerMapper.insert(demoEngineer);

        com.ses.entity.Invoice invoice = new com.ses.entity.Invoice();
        invoice.setInvoiceNo("INV-DEMO-A");
        invoice.setCustomerId(demoCustomer.getId());
        invoice.setBillingMonth(month);
        invoice.setStatus("送付済");
        invoice.setIssuedDate(today);
        invoice.setDueDate(today.plusMonths(1));
        invoice.setSubtotal(new BigDecimal("1000000"));
        invoice.setTax(new BigDecimal("100000"));
        invoice.setTotal(new BigDecimal("1100000"));
        invoice.setTaxRate(new BigDecimal("0.100"));
        invoiceMapper.insert(invoice);

        com.ses.entity.WorkRecord workRecord = new com.ses.entity.WorkRecord();
        workRecord.setContractId(1L);
        workRecord.setWorkMonth(month);
        workRecord.setActualHours(new BigDecimal("160.00"));
        workRecordMapper.insert(workRecord);

        com.ses.entity.BpPayment bpPayment = new com.ses.entity.BpPayment();
        bpPayment.setWorkRecordId(workRecord.getId());
        bpPayment.setAmount(new BigDecimal("800000"));
        bpPayment.setStatus("承認済");
        bpPayment.setPayeeCompanyName("BPデモ会社");
        bpPaymentMapper.insert(bpPayment);

        com.ses.entity.ExpenseRequest expense = new com.ses.entity.ExpenseRequest();
        expense.setEngineerId(demoEngineer.getId());
        expense.setExpenseNo("EX-DEMO-A");
        expense.setCategory("交通費");
        expense.setExpenseDate(today);
        expense.setAmount(new BigDecimal("15000"));
        expense.setStatus("承認済");
        expenseRequestMapper.insert(expense);

        com.ses.entity.InvoicePayment payment = new com.ses.entity.InvoicePayment();
        payment.setInvoiceId(invoice.getId());
        payment.setPaidDate(today);
        payment.setAmount(new BigDecimal("1000000"));
        payment.setFee(new BigDecimal("20000"));
        invoicePaymentMapper.insert(payment);

        // 4母集団それぞれの SUCCEEDED ジョブ (external deal id は stub 応答と一致)
        jobService.createJob(conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", invoice.getId(), "INV-DEMO-SYNC-A", "hash-a",
                "{\"invoiceId\":" + invoice.getId() + "}", conn.getTenantId(), conn.getLegalEntityId(), null);
        IntegrationJob invJob = jobService.getLatestJob("INVOICE", invoice.getId(), "SALES_INVOICE_SYNC");
        jobService.claimJob(invJob.getId());
        jobService.markSucceeded(invJob.getId(), "1001", "req-demo-a", "同期成功");

        jobService.createJob(conn.getId(), "BP_PURCHASE_SYNC", "BP_PAYMENT", bpPayment.getId(), "BP-DEMO-SYNC-B", "hash-b",
                "{\"bpPaymentId\":" + bpPayment.getId() + "}", conn.getTenantId(), conn.getLegalEntityId(), null);
        IntegrationJob bpJob = jobService.getLatestJob("BP_PAYMENT", bpPayment.getId(), "BP_PURCHASE_SYNC");
        jobService.claimJob(bpJob.getId());
        jobService.markSucceeded(bpJob.getId(), "1002", "req-demo-b", "同期成功");

        jobService.createJob(conn.getId(), "EXPENSE_DEAL_SYNC", "EXPENSE_REQUEST", expense.getId(), "EX-DEMO-SYNC-C", "hash-c",
                "{\"expenseId\":" + expense.getId() + "}", conn.getTenantId(), conn.getLegalEntityId(), null);
        IntegrationJob expJob = jobService.getLatestJob("EXPENSE_REQUEST", expense.getId(), "EXPENSE_DEAL_SYNC");
        jobService.claimJob(expJob.getId());
        jobService.markSucceeded(expJob.getId(), "1003", "req-demo-c", "同期成功");

        // freee stub 応答: 4母集団に一致する deal (401復旧後の2回目以降に返る)
        dealsJson = "{\"deals\": ["
                + "{\"id\": 1001, \"issue_date\": \"" + today + "\", \"amount\": 1100000, \"ref_number\": \"INV-DEMO-A\", \"status\": \"settled\"},"
                + "{\"id\": 1002, \"issue_date\": \"" + today + "\", \"amount\": 800000, \"ref_number\": \"BP-" + bpPayment.getId() + "\", \"status\": \"settled\"},"
                + "{\"id\": 1003, \"issue_date\": \"" + today + "\", \"amount\": 15000, \"ref_number\": \"EX-DEMO-A\", \"status\": \"settled\"},"
                + "{\"id\": 1004, \"issue_date\": \"" + today + "\", \"amount\": 1020000, \"ref_number\": \"PAY-DEMO-1004\", \"status\": \"settled\"}"
                + "]}";

        // ===== マネージャー (組織 X) と自組織ジョブ =====
        OrganizationUnit orgX = OrganizationUnit.builder().tenantId(1L).legalEntityId(1L).code("DEMO-ORG-X")
                .name("デモ組織X").type("部門").validFrom(LocalDate.of(2026, 1, 1)).status("有効").version(0).build();
        organizationUnitMapper.insert(orgX);
        managerOrgId = orgX.getId();

        com.ses.entity.SysUser managerUser = new com.ses.entity.SysUser();
        managerUser.setUsername("manager_demo");
        managerUser.setPassword("pass");
        managerUser.setRealName("デモマネージャー");
        managerUser.setRole("マネージャー");
        managerUser.setStatus(1);
        sysUserMapper.insert(managerUser);
        userOrganizationMapper.insert(UserOrganization.builder()
                .userId(managerUser.getId()).organizationId(orgX.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());

        IntegrationJob job2 = jobService.createJob(
                conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", 202L, "INV-DEMO-002", "hash-demo-202");
        jobService.claimJob(job2.getId());
        jobService.markRetryable(job2.getId(), "TIMEOUT", "一時的タイムアウト", 300);
        IntegrationJob managerJob = jobService.getById(job2.getId());
        managerJob.setOrganizationId(orgX.getId());
        jobService.updateById(managerJob);
        managerJobId = job2.getId();

        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of(".kiro", "specs", "accounting-payment-integration", "evidence", "browser");
        Files.createDirectories(evidenceDir);
        String runId = "browser-demo-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.writeString(evidenceDir.resolve("run-id.txt"), runId + "\n");

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("runId", runId);
        summary.put("baseUrl", baseUrl);
        summary.put("connectionId", conn.getId());
        summary.put("jobId", job1.getId());
        summary.put("managerJobId", job2.getId());
        summary.put("managerOrgId", orgX.getId());
        summary.put("reconciliationMonth", month);

        runViewport(chrome, baseUrl, evidenceDir, runId, "desktop", 1920, 1080, conn.getId(), summary);
        runViewport(chrome, baseUrl, evidenceDir, runId, "mobile390", 390, 844, conn.getId(), summary);
        runManagerBoundary(chrome, baseUrl, evidenceDir, runId, summary);

        // 401 自動復旧の実演検証: 初回 deals 呼出は 401 -> OAuth 更新 -> リプレイ成功
        assertTrue(deals401Count.get() >= 1, "freee stub が初回のトークン失効 (401) を再現していること");
        assertTrue(tokenRefreshCount.get() >= 1, "401 検知後に OAuth トークン更新が実行されていること");
        summary.put("deals401Count", deals401Count.get());
        summary.put("tokenRefreshCount", tokenRefreshCount.get());

        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("Accounting Browser Demo done: " + evidenceDir + " (runId=" + runId + ")");
    }

    private void runViewport(Path chrome, String baseUrl, Path evidenceDir, String runId,
                             String viewport, int width, int height, Long targetConnId, ObjectNode summary) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-accounting-" + viewport);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            // 1. 管理者ログイン
            login(browser, baseUrl, "admin", "admin123", viewport);

            // 2. 会計連携画面へ遷移
            browser.navigate(baseUrl + "/accounting/integration");
            assertTrue(browser.waitFor("document.readyState === 'complete' && document.querySelector('#accountingTabs') !== null", java.time.Duration.ofSeconds(30)),
                    "[" + viewport + "] 会計連携画面のタブが表示されること");

            // 初期データロード実行 & 待機
            browser.evaluate("if(window.AccountingIntegration) { if(window.AccountingIntegration.loadJobs) window.AccountingIntegration.loadJobs(1); if(window.AccountingIntegration.loadConnections) window.AccountingIntegration.loadConnections(); }");

            // Jobs タブ: 実ジョブ行 (全社共通 + 4母集団 + マネージャー組織) が描画される
            browser.waitFor("document.querySelectorAll('#jobsTbody tr').length > 0 && document.querySelector('#jobsTbody td').getAttribute('colspan') === null", java.time.Duration.ofSeconds(20));
            int jobRows = browser.evaluate("document.querySelectorAll('#jobsTbody tr').length").asInt(-1);
            assertTrue(jobRows >= 5, "[" + viewport + "] Jobs テーブルに実ジョブ行 (全社共通+マネージャー組織+4母集団 5件以上) が描画されていること (actual=" + jobRows + ")");

            Path jobsPng = evidenceDir.resolve(viewport + "-01-jobs.png");
            safeWriteFile(jobsPng, browser.screenshot());

            // Mappings タブ
            browser.evaluate("document.querySelector('#mappings-tab') && document.querySelector('#mappings-tab').click()");
            browser.waitFor("document.querySelectorAll('#mappingConnSelect option').length > 0", java.time.Duration.ofSeconds(15));
            browser.evaluate("if(window.AccountingIntegration) { $('#mappingConnSelect').val('" + targetConnId + "').trigger('change'); window.AccountingIntegration.loadMappings(" + targetConnId + "); }");
            browser.waitFor("document.querySelectorAll('#mappingsTbody tr').length > 0 && document.querySelector('#mappingsTbody td').getAttribute('colspan') === null", java.time.Duration.ofSeconds(15));
            boolean hasMappingRows = browser.evaluate("document.querySelectorAll('#mappingsTbody tr').length > 0 && document.querySelector('#mappingsTbody td').getAttribute('colspan') === null").asBoolean(false);
            assertTrue(hasMappingRows, "[" + viewport + "] Mappings テーブルに実マッピング行が描画されていること");
            Path mappingsPng = evidenceDir.resolve(viewport + "-02-mappings.png");
            safeWriteFile(mappingsPng, browser.screenshot());

            // Connections タブ
            browser.evaluate("document.querySelector('#connections-tab') && document.querySelector('#connections-tab').click()");
            browser.evaluate("if(window.AccountingIntegration && window.AccountingIntegration.loadConnections) { window.AccountingIntegration.loadConnections(); }");
            browser.waitFor("document.querySelectorAll('#connectionsContainer .card').length > 0", java.time.Duration.ofSeconds(15));
            boolean hasConnRows = browser.evaluate("document.querySelectorAll('#connectionsContainer .card').length > 0").asBoolean(false);
            assertTrue(hasConnRows, "[" + viewport + "] Connections コンテナに接続カードが描画されていること");
            Path connPng = evidenceDir.resolve(viewport + "-03-connections.png");
            safeWriteFile(connPng, browser.screenshot());

            // Preview タブ
            browser.evaluate("document.querySelector('#preview-tab') && document.querySelector('#preview-tab').click()");
            Thread.sleep(500);
            Path previewPng = evidenceDir.resolve(viewport + "-04-preview.png");
            safeWriteFile(previewPng, browser.screenshot());

            // Reconciliation タブ: 4母集団 MATCHED + 締可 badge (初回は 401 -> 自動復旧 -> 200)
            browser.evaluate("document.querySelector('#reconciliation-tab') && document.querySelector('#reconciliation-tab').click()");
            browser.waitFor("document.querySelector('#summaryClosingBadge') !== null && document.querySelector('#summaryClosingBadge').textContent.includes('締')", java.time.Duration.ofSeconds(30));
            String closingBadge = browser.evaluate("document.querySelector('#summaryClosingBadge').textContent").asText("");
            assertTrue(closingBadge.contains("締可"), "[" + viewport + "] 月次照合が4母集団完全一致で締可表示になること (badge=" + closingBadge + ")");
            int reconRows = browser.evaluate("document.querySelectorAll('#reconcileTbody tr').length").asInt(-1);
            assertTrue(reconRows >= 4, "[" + viewport + "] 照合テーブルに4母集団のMATCHED行が描画されていること (actual=" + reconRows + ")");
            Thread.sleep(500);
            Path reconPng = evidenceDir.resolve(viewport + "-05-reconciliation.png");
            safeWriteFile(reconPng, browser.screenshot());

            // console error 0
            assertConsoleErrorsZero(browser, viewport);

            ObjectNode vpNode = MAPPER.createObjectNode();
            vpNode.put("jobsScreenshot", jobsPng.getFileName().toString());
            vpNode.put("mappingsScreenshot", mappingsPng.getFileName().toString());
            vpNode.put("connectionsScreenshot", connPng.getFileName().toString());
            vpNode.put("previewScreenshot", previewPng.getFileName().toString());
            vpNode.put("reconciliationScreenshot", reconPng.getFileName().toString());
            vpNode.put("jobRows", jobRows);
            vpNode.put("reconciliationRows", reconRows);
            vpNode.put("closingBadge", closingBadge);
            vpNode.put("consoleErrors", 0);
            vpNode.put("domAssertPass", true);
            summary.set(viewport, vpNode);
        }
    }

    /** マネージャー境界: 自組織ジョブのみ可視・全社共通ジョブ不可視・照合は要確認。 */
    private void runManagerBoundary(Path chrome, String baseUrl, Path evidenceDir, String runId, ObjectNode summary) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-accounting-manager");
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, 1920, 1080)) {
            login(browser, baseUrl, "manager_demo", "pass", "manager");

            browser.navigate(baseUrl + "/accounting/integration");
            assertTrue(browser.waitFor("document.readyState === 'complete' && document.querySelector('#accountingTabs') !== null", java.time.Duration.ofSeconds(30)),
                    "[manager] 会計連携画面のタブが表示されること");
            browser.evaluate("if(window.AccountingIntegration && window.AccountingIntegration.loadJobs) window.AccountingIntegration.loadJobs(1);");
            browser.waitFor("document.querySelectorAll('#jobsTbody tr').length > 0", java.time.Duration.ofSeconds(20));
            String jobText = browser.evaluate("document.querySelector('#jobsTbody').textContent").asText("");
            assertTrue(jobText.contains("#" + managerJobId), "[manager] 自組織ジョブが可視であること");
            assertTrue(!jobText.contains("#" + adminJobId), "[manager] 全社共通 (organization_id NULL) ジョブが不可視であること (認可境界)");

            // 照合: 組織外の内部データは不可視のため外部のみ取引となり「要確認 (締不可)」
            browser.evaluate("document.querySelector('#reconciliation-tab') && document.querySelector('#reconciliation-tab').click()");
            browser.waitFor("document.querySelector('#summaryClosingBadge') !== null && document.querySelector('#summaryClosingBadge').textContent.includes('締')", java.time.Duration.ofSeconds(30));
            String managerBadge = browser.evaluate("document.querySelector('#summaryClosingBadge').textContent").asText("");
            assertTrue(managerBadge.contains("要確認") || managerBadge.contains("締不可"),
                    "[manager] 組織外データ不可視により締め不可 (要確認) 表示になること (badge=" + managerBadge + ")");

            assertConsoleErrorsZero(browser, "manager");

            Path boundaryPng = evidenceDir.resolve("desktop-06-manager-boundary.png");
            safeWriteFile(boundaryPng, browser.screenshot());

            ObjectNode mgrNode = MAPPER.createObjectNode();
            mgrNode.put("managerJobVisible", true);
            mgrNode.put("adminOnlyJobHidden", true);
            mgrNode.put("closingBadge", managerBadge);
            mgrNode.put("consoleErrors", 0);
            mgrNode.put("boundaryScreenshot", boundaryPng.getFileName().toString());
            summary.set("managerBoundary", mgrNode);
        }
    }

    private void login(CdpBrowser browser, String baseUrl, String username, String password, String label) throws Exception {
        browser.navigate(baseUrl + "/login");
        assertTrue(browser.waitFor("document.readyState === 'complete' && document.getElementById('username') !== null", java.time.Duration.ofSeconds(30)),
                "[" + label + "] ログインフォームが表示されること");
        browser.evaluate("(function(){"
                + "var u=document.getElementById('username');"
                + "var p=document.getElementById('password');"
                + "if(!u||!p){return 'MISSING_FIELDS';}"
                + "u.value='" + username + "';p.value='" + password + "';"
                + "var f=u.closest('form');"
                + "if(!f){return 'NO_FORM';}"
                + "f.submit();return 'SUBMITTED';})()");
        boolean loggedIn = browser.waitFor("location.pathname !== '/login'", java.time.Duration.ofSeconds(30));
        assertTrue(loggedIn, "[" + label + "] ログインで/loginから離脱すること");
    }

    private void assertConsoleErrorsZero(CdpBrowser browser, String label) throws Exception {
        long errors = browser.consoleEvents().stream()
                .filter(ev -> {
                    String type = ev.path("params").path("type").asText("");
                    String level = ev.path("params").path("entry").path("level").asText("");
                    return "error".equals(type) || "error".equals(level);
                })
                .count();
        assertEquals(0, errors, "[" + label + "] ブラウザ console error が 0 であること");
    }

    private static void safeWriteFile(Path path, byte[] bytes) throws Exception {
        Path temp = Files.createTempFile(path.getParent(), "scr-", ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
