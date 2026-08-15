package com.ses.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Contract;
import com.ses.entity.ProjectPosition;
import com.ses.entity.StaffingScenario;
import com.ses.entity.StaffingScenarioAllocation;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.ContractService;
import com.ses.service.staffing.AllocationPlanService;
import com.ses.service.staffing.StaffingScenarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T080（M）browser Demo: staffing一気通貫（position→配置→heatmap→scenario compare）を
 * 実Chrome（CDP・headless）でdesktop（1920x1080）と390px（390x844）で実測し、
 * DOM検証・スクリーンショット・コンソールイベントを `evidence/browser-m/` へ保存する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StaffingBrowserMTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectPositionMapper positionMapper;

    @Autowired
    private AllocationPlanService allocationService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private StaffingScenarioService scenarioService;

    @Test
    @DisplayName("T080-M: 実Chromeでstaffing画面（board/timeline/heatmap/scenario）をdesktop/390pxで実測")
    void captureStaffingScreensWithRealBrowser() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");
        DemoData demo = seedDemoData();
        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of(".kiro", "specs", "staffing-capacity-planning", "evidence", "browser-m");
        Files.createDirectories(evidenceDir);
        String runId = "browser-m-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.writeString(evidenceDir.resolve("run-id.txt"), runId + "\n");

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("runId", runId);
        summary.put("baseUrl", baseUrl);
        summary.put("projectId", demo.projectId);
        summary.put("engineerId", demo.engineerId);
        summary.put("scenarioId", demo.scenarioId);

        runViewport(chrome, baseUrl, evidenceDir, runId, "desktop", 1920, 1080, demo, summary);
        runViewport(chrome, baseUrl, evidenceDir, runId, "mobile390", 390, 844, demo, summary);

        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("T080-M browser Demo done: " + evidenceDir + " (runId=" + runId + ")");
    }

    private void runViewport(Path chrome, String baseUrl, Path evidenceDir, String runId,
                             String viewport, int width, int height, DemoData demo, ObjectNode summary) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-" + viewport);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            // ---- 1. ログイン ----
            browser.navigate(baseUrl + "/login");
            waitFor(browser, "document.querySelector('#username') !== null || document.querySelector('input[name=username]') !== null");
            browser.evaluate("(function(){" +
                    "var u=document.querySelector('#username')||document.querySelector('input[name=username]');" +
                    "var p=document.querySelector('#password')||document.querySelector('input[name=password]');" +
                    "if(!u||!p)return false;u.value='admin';p.value='admin123';return true;})()");
            browser.evaluate("(function(){var f=document.querySelector('form');if(f)f.submit();return !!f;})()");
            waitFor(browser, "window.location.pathname !== '/login'");
            String afterLogin = browser.evaluate("window.location.pathname").asText("");
            assertTrue(!afterLogin.equals("/login"), "[" + viewport + "] ログイン後に/loginから遷移すること");

            // ---- 2. 案件詳細: ポジションボード ----
            browser.navigate(baseUrl + "/project/detail?id=" + demo.projectId);
            waitFor(browser, "document.getElementById('position-board-body') !== null");
            waitFor(browser, "document.querySelectorAll('.staff-alloc-card').length >= 1");
            int cards = browser.evaluate("document.querySelectorAll('.staff-alloc-card').length").asInt(0);
            assertTrue(cards >= 1, "[" + viewport + "] ポジションボードに配置カードが表示される");
            int columns = browser.evaluate("document.querySelectorAll('#position-board-columns .card').length").asInt(0);
            assertTrue(columns >= 1, "[" + viewport + "] ポジション列が表示される");
            saveShot(browser, evidenceDir, viewport + "-position-board.png", summary);

            // ---- 3. 要員詳細: 配置計画タブ ----
            browser.navigate(baseUrl + "/engineer/detail?id=" + demo.engineerId);
            waitFor(browser, "document.getElementById('staffing-tab') !== null");
            browser.evaluate("document.getElementById('staffing-tab').click(); true");
            waitFor(browser, "document.getElementById('staffing-timeline-body') !== null");
            waitFor(browser, "document.getElementById('staffing-timeline-body').textContent.indexOf('実績') >= 0 "
                    + "|| document.getElementById('staffing-timeline-body').textContent.indexOf('計画') >= 0");
            saveShot(browser, evidenceDir, viewport + "-engineer-timeline.png", summary);

            // ---- 4. 需給ヒートマップ ----
            browser.navigate(baseUrl + "/analytics/staffing-heatmap");
            waitFor(browser, "document.getElementById('role-table') !== null");
            waitFor(browser, "document.getElementById('role-table').rows.length > 1");
            int heatRows = browser.evaluate("document.getElementById('role-table').rows.length").asInt(0);
            assertTrue(heatRows > 1, "[" + viewport + "] ヒートマップのrole表が描画される");
            saveShot(browser, evidenceDir, viewport + "-heatmap.png", summary);

            // ---- 5. シナリオ比較 ----
            browser.navigate(baseUrl + "/analytics/staffing-scenario-compare");
            waitFor(browser, "document.getElementById('scenario-select') !== null");
            waitFor(browser, "document.getElementById('scenario-select').options.length >= 1");
            browser.evaluate("document.getElementById('scenario-select').value = '" + demo.scenarioId + "'; true");
            browser.evaluate("document.getElementById('scenario-compare-btn').click(); true");
            waitFor(browser, "document.getElementById('scenario-compare-result') !== null "
                    + "&& !document.getElementById('scenario-compare-result').classList.contains('d-none')");
            saveShot(browser, evidenceDir, viewport + "-scenario-compare.png", summary);

            // ---- 6. コンソールイベント保存 ----
            List<com.fasterxml.jackson.databind.JsonNode> console = browser.consoleEvents();
            StringBuilder consoleLog = new StringBuilder();
            for (var event : console) {
                consoleLog.append(event.toString()).append('\n');
            }
            Files.writeString(evidenceDir.resolve(viewport + "-console.txt"), consoleLog.toString());
            summary.put(viewport + "-consoleCount", console.size());
            summary.put(viewport + "-cards", cards);
            summary.put(viewport + "-columns", columns);
            summary.put(viewport + "-heatRows", heatRows);
        } finally {
            deleteRecursively(profile);
        }
    }

    private void saveShot(CdpBrowser browser, Path evidenceDir, String name, ObjectNode summary) throws Exception {
        byte[] png = browser.screenshot();
        Path pngPath = evidenceDir.resolve(name);
        Files.write(pngPath, png);
        summary.put(name, pngPath.getFileName().toString());
        summary.put(name + "-sha256", sha256(png));
    }

    private void waitFor(CdpBrowser browser, String jsExpression) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(40).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (browser.evaluate(jsExpression).asBoolean(false)) {
                return;
            }
            Thread.sleep(250);
        }
        String url = browser.evaluate("window.location.href").asText("");
        String body = browser.evaluate("document.body ? document.body.innerText.slice(0, 300) : '(no body)'").asText("");
        throw new AssertionError("waitFor timeout: " + jsExpression + " url=" + url + " body=" + body);
    }

    private String sha256(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // ベストエフォート
                }
            });
        } catch (Exception ignored) {
            // ベストエフォート
        }
    }

    /** position→配置→契約（actual）→scenarioまでをシードする。 */
    private DemoData seedDemoData() {
        // scenario作成は現在ユーザーをownerにするため、テスト内で認証してからシードする
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "92001", "n/a", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_管理者"))));
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T080demo-" + suffix);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T080demo-" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", "T080demo-prj-" + suffix, customerId);
        long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T080demo-prj-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, expected_unit_price) "
                + "VALUES (?, '正社員', '稼動中', 800000)", "T080demo-eng-" + suffix);
        long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T080demo-eng-" + suffix);

        ProjectPosition position = new ProjectPosition();
        position.setProjectId(projectId);
        position.setPositionNo("P1");
        position.setRoleName("Javaエンジニア");
        position.setRequiredCount(1);
        position.setAllocationPercent(new BigDecimal("100"));
        position.setSkillsJson("[\"Java\",\"Spring\"]");
        position.setStartDate(LocalDate.of(2026, 9, 1));
        position.setEndDate(LocalDate.of(2026, 12, 31));
        positionMapper.insert(position);

        AllocationPlan plan = new AllocationPlan();
        plan.setEngineerId(engineerId);
        plan.setPositionId(position.getId());
        plan.setAllocationType(AllocationPlan.TYPE_PROJECT);
        plan.setStartDate(LocalDate.of(2026, 9, 1));
        plan.setEndDate(LocalDate.of(2026, 12, 31));
        plan.setAllocationPercent(new BigDecimal("100"));
        allocationService.saveDraft(plan);

        // 契約（actual）: ポジション紐付けでactual allocationが作られる
        Contract contract = new Contract();
        contract.setEngineerId(engineerId);
        contract.setProjectId(projectId);
        contract.setCustomerId(customerId);
        contract.setContractType("準委任");
        contract.setStartDate(LocalDate.of(2026, 9, 1));
        contract.setEndDate(LocalDate.of(2026, 12, 31));
        contract.setSellingPrice(new BigDecimal("900000"));
        contract.setCostPrice(new BigDecimal("700000"));
        contract.setPositionId(position.getId());
        contractService.saveWithBusinessRules(contract);

        // scenario（比較対象）
        StaffingScenario scenario = scenarioService.create(scenario("T080案A", suffix));
        StaffingScenarioAllocation allocation = new StaffingScenarioAllocation();
        allocation.setScenarioId(scenario.getId());
        allocation.setEngineerId(engineerId);
        allocation.setPositionId(position.getId());
        allocation.setPercent(new BigDecimal("100"));
        allocation.setDates("[\"2026-09-01\",\"2026-09-02\",\"2026-09-03\",\"2026-09-04\",\"2026-09-05\"]");
        scenarioService.upsertAllocation(allocation);
        StaffingScenario scenarioB = scenarioService.create(scenario("T080案B", suffix));
        StaffingScenarioAllocation allocationB = new StaffingScenarioAllocation();
        allocationB.setScenarioId(scenarioB.getId());
        allocationB.setEngineerId(engineerId);
        allocationB.setPositionId(position.getId());
        allocationB.setPercent(new BigDecimal("50"));
        allocationB.setDates("[\"2026-09-01\",\"2026-09-02\",\"2026-09-03\"]");
        scenarioService.upsertAllocation(allocationB);

        return new DemoData(projectId, engineerId, scenario.getId());
    }

    private StaffingScenario scenario(String name, String suffix) {
        StaffingScenario s = new StaffingScenario();
        s.setName(name);
        s.setBaseDate(LocalDate.of(2026, 8, 1));
        s.setSharedFlag(1);
        return s;
    }

    private record DemoData(long projectId, long engineerId, long scenarioId) {
    }
}
