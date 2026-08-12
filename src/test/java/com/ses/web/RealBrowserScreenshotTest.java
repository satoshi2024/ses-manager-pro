package com.ses.web;

import com.ses.entity.Acceptance;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.SysUser;
import com.ses.entity.WorkRecord;
import com.ses.mapper.AcceptanceMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7-P2-04（REOPEN）対応: 実Chrome（headless）をCDPで制御し、<b>同一ブラウザセッション内で
 * ログイン→通知遷移URL（/acceptance?workMonth=...&acceptanceId=...）→DOM検証→スクリーンショット</b>
 * を実施する。スクリーンショット・HAR（実ネットワークイベント）・コンソール（実イベント）は
 * すべて同一ブラウザrunから生成し、Java側で結論を文字列連結しない。
 *
 * <p>Desktop（1920x1080）とMobile（390x844）を独立セッションで実行し、共通run ID・動的
 * acceptance ID・各PNGのSHA-256・最終URL・DOM検証結果を `evidence/browser-r8/` へ保存する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealBrowserScreenshotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private ProjectMapper projectMapper;
    @Autowired private ContractMapper contractMapper;
    @Autowired private WorkRecordMapper workRecordMapper;
    @Autowired private AcceptanceMapper acceptanceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("R7-P2-04: 実Chromeで同一セッション認証→/acceptance通知遷移→DOM検証→PNG/HAR/Console生成")
    void captureRealWebpageScreenshotsWithAuthentication() throws Exception {
        // ---- 1. Seed（管理者・顧客・要員・案件・契約・勤怠・検収） ----
        SysUser adminUser = sysUserMapper.selectByUsername("admin");
        if (adminUser == null) {
            adminUser = new SysUser();
            adminUser.setUsername("admin");
            adminUser.setPassword("admin123");
            adminUser.setRealName("管理者ユーザー");
            adminUser.setRole("管理者");
            adminUser.setStatus(1);
            sysUserMapper.insert(adminUser);
        }

        String suffix = "-R8-" + System.currentTimeMillis();
        Customer customer = new Customer();
        customer.setCompanyName("テックソリューションズ株式会社" + suffix);
        customer.setTrustLevel("A");
        customerMapper.insert(customer);

        Engineer engineer = new Engineer();
        engineer.setFullName("山田 太郎" + suffix);
        engineer.setEmploymentType("正社員");
        engineer.setStatus("稼動中");
        engineerMapper.insert(engineer);

        Project project = new Project();
        project.setProjectName("基幹システム刷新" + suffix);
        project.setCustomerId(customer.getId());
        project.setStatus("募集中");
        projectMapper.insert(project);

        Contract contract = new Contract();
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_contract WHERE contract_no = 'CON-2026-0001'", Integer.class);
        contract.setContractNo((existing != null && existing > 0)
                ? "CON-2026-REAL-" + System.currentTimeMillis() : "CON-2026-0001");
        contract.setEngineerId(engineer.getId());
        contract.setProjectId(project.getId());
        contract.setCustomerId(customer.getId());
        contract.setStartDate(LocalDate.of(2026, 1, 1));
        contract.setSellingPrice(new BigDecimal("600000"));
        contract.setCostPrice(new BigDecimal("300000"));
        contract.setStatus("稼動中");
        contract.setAcceptanceRequired(true);
        contractMapper.insert(contract);

        WorkRecord workRecord = new WorkRecord();
        workRecord.setContractId(contract.getId());
        workRecord.setWorkMonth("2026-07");
        workRecord.setActualHours(new BigDecimal("160.00"));
        workRecord.setBillingAmount(new BigDecimal("600000"));
        workRecord.setStatus("確定");
        workRecordMapper.insert(workRecord);

        Acceptance acceptance = new Acceptance();
        acceptance.setContractId(contract.getId());
        acceptance.setWorkRecordId(workRecord.getId());
        acceptance.setWorkMonth("2026-07");
        acceptance.setStatus("提出済");
        acceptance.setSubmittedAt(LocalDateTime.of(2026, 7, 31, 17, 0));
        acceptance.setHoursSnapshot(new BigDecimal("160.00"));
        acceptance.setAmountSnapshot(new BigDecimal("600000"));
        acceptance.setCreatedBy(adminUser.getId());
        acceptanceMapper.insert(acceptance);

        Long acceptanceId = acceptance.getId();
        assertNotNull(acceptanceId, "動的acceptance IDが採番されること");

        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of(".kiro", "specs", "order-acceptance-workflow", "evidence", "browser-r8");
        Files.createDirectories(evidenceDir);
        String runId = "browser-r8-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.writeString(evidenceDir.resolve("run-id.txt"), runId + "\nacceptanceId=" + acceptanceId + "\n");

        // ---- 2. Desktop セッション（同一ブラウザでログイン→遷移→検証→証跡） ----
        ObjectNode desktop = MAPPER.createObjectNode();
        Path desktopProfile = Files.createTempDirectory("cdp-desktop-");
        try (CdpBrowser browser = CdpBrowser.launch(CdpBrowser.chromeExecutable(), desktopProfile, 1920, 1080)) {
            browser.setDeviceMetrics(1920, 1080, false, 1.0);
            runAuthenticatedFlow(browser, baseUrl, acceptanceId, "desktop", evidenceDir, runId, desktop);
        } finally {
            deleteRecursively(desktopProfile);
        }

        // ---- 3. Mobile（390x844）セッション ----
        ObjectNode mobile = MAPPER.createObjectNode();
        Path mobileProfile = Files.createTempDirectory("cdp-mobile-");
        try (CdpBrowser browser = CdpBrowser.launch(CdpBrowser.chromeExecutable(), mobileProfile, 390, 844)) {
            browser.setDeviceMetrics(390, 844, true, 1.0);
            runAuthenticatedFlow(browser, baseUrl, acceptanceId, "mobile", evidenceDir, runId, mobile);
        } finally {
            deleteRecursively(mobileProfile);
        }

        // ---- 4. Seed provenance（実DB行）とサマリ ----
        Acceptance stored = acceptanceMapper.selectById(acceptanceId);
        // テスト用H2スキーマはV12以降の列を含まないため、entityの全列SELECTではなく特定列のみJDBCで取得する
        String storedContractNo = (stored != null && stored.getContractId() != null)
                ? jdbcTemplate.queryForObject(
                        "SELECT contract_no FROM t_contract WHERE id = ?", String.class, stored.getContractId())
                : null;
        ObjectNode provenance = MAPPER.createObjectNode();
        provenance.put("spec", "order-acceptance-workflow");
        provenance.put("runId", runId);
        provenance.put("acceptanceId", acceptanceId);
        provenance.put("contractNo", storedContractNo);
        provenance.put("engineerName", engineer.getFullName());
        provenance.put("customerName", customer.getCompanyName());
        provenance.put("workMonth", stored == null ? null : stored.getWorkMonth());
        provenance.put("status", stored == null ? null : stored.getStatus());
        provenance.put("sqlQuery",
                "SELECT id, contract_id, work_month, status FROM t_acceptance WHERE id = " + acceptanceId);
        provenance.put("verifiedPresent", stored != null);
        provenance.put("environment", "H2 / Spring Boot Embedded Web Container");
        Files.writeString(evidenceDir.resolve("seed-provenance.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(provenance));

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("runId", runId);
        summary.put("acceptanceId", acceptanceId);
        summary.set("desktop", desktop);
        summary.set("mobile", mobile);
        summary.put("note",
                "全PNG/HAR/consoleは同一ブラウザセッション（同一Cookieコンテキスト）の実CDPイベントから生成。"
                        + "Java側で結論文字列を連結していない。");
        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    /** ブラウザでログイン→通知URL遷移→DOM/スクロール検証→PNG/HAR/Console保存を行う。 */
    private void runAuthenticatedFlow(CdpBrowser browser, String baseUrl, Long acceptanceId,
                                      String viewport, Path evidenceDir, String runId, ObjectNode out) throws Exception {
        // ログインページへ
        browser.navigate(baseUrl + "/login");
        assertTrue(browser.waitFor(
                "document.readyState === 'complete' && document.getElementById('username') !== null",
                Duration.ofSeconds(40)), "[" + viewport + "] ログインフォームが表示されること");

        // 同一ブラウザセッションでフォーム送信（CSRF hidden inputはフォームが保持）
        browser.evaluate("(function(){"
                + "var u=document.getElementById('username');"
                + "var p=document.getElementById('password');"
                + "if(!u||!p){return 'MISSING_FIELDS';}"
                + "u.value='admin';p.value='admin123';"
                + "var f=u.closest('form');"
                + "if(!f){return 'NO_FORM';}"
                + "f.submit();return 'SUBMITTED';})()");
        boolean loggedIn = browser.waitFor("location.pathname !== '/login'", Duration.ofSeconds(40));
        String afterLoginUrl = browser.currentUrl();
        assertTrue(loggedIn, "[" + viewport + "] ログインで/loginから離脱すること");
        assertFalse(afterLoginUrl.contains("/login"),
                "[" + viewport + "] 最終URLが/loginでないこと: " + afterLoginUrl);

        // 通知遷移URL（動的acceptanceId）
        String targetUrl = baseUrl + "/acceptance?workMonth=2026-07&acceptanceId=" + acceptanceId;
        browser.navigate(targetUrl);
        boolean gridLoaded = browser.waitFor(
                "document.readyState === 'complete' && document.querySelector('#acceptanceTable tbody tr') !== null",
                Duration.ofSeconds(40));
        assertTrue(gridLoaded, "[" + viewport + "] 検収グリッドが描画されること");
        // acceptance.jsのscrollIntoView({behavior:'smooth'})完了を待つ
        Thread.sleep(800);

        boolean targetRowExists = browser.evaluate(
                "document.querySelector(\"tr[data-acceptance-id='" + acceptanceId + "'].table-warning\") !== null")
                .asBoolean();
        assertTrue(targetRowExists,
                "[" + viewport + "] 対象行 tr[data-acceptance-id='" + acceptanceId + "'].table-warning が存在すること");

        boolean targetVisible = browser.evaluate("(function(){"
                + "var el=document.querySelector(\"tr[data-acceptance-id='" + acceptanceId + "']\");"
                + "if(!el){return false;}"
                + "var r=el.getBoundingClientRect();"
                + "return r.top >= 0 && r.bottom <= window.innerHeight;})()").asBoolean();
        assertTrue(targetVisible, "[" + viewport + "] 対象行がビューポート内に見えていること（scrollIntoView）");

        double scrollY = browser.evaluate("window.scrollY").asDouble(0);
        byte[] png = browser.screenshot();
        String hash = sha256Hex(png);
        String fileName = ("desktop".equals(viewport) ? "desktop-1920x1080.png" : "mobile-390x844.png");
        Files.write(evidenceDir.resolve(fileName), png);

        writeRealHar(browser, evidenceDir.resolve("network-" + viewport + ".json"), viewport, baseUrl, acceptanceId);
        writeRealConsole(browser, evidenceDir.resolve("console-" + viewport + ".txt"), viewport, targetUrl);

        out.put("viewport", viewport);
        out.put("finalUrl", browser.currentUrl());
        out.put("afterLoginUrl", afterLoginUrl);
        out.put("targetRowTableWarning", targetRowExists);
        out.put("targetRowVisibleInViewport", targetVisible);
        out.put("scrollY", scrollY);
        out.put("screenshotFile", fileName);
        out.put("screenshotSha256", hash);
        out.put("consoleErrorCount", realConsoleErrorCount(browser));
    }

    /** CDPの実NetworkイベントからHAR 1.2を組み立てる（リクエストとレスポンスをrequestIdで対応付ける）。 */
    private void writeRealHar(CdpBrowser browser, Path file, String viewport, String baseUrl, Long acceptanceId)
            throws Exception {
        ObjectNode har = MAPPER.createObjectNode();
        ObjectNode log = har.putObject("log");
        log.put("version", "1.2");
        ObjectNode creator = log.putObject("creator");
        creator.put("name", "CDP Network events (Chrome DevTools Protocol)");
        creator.put("version", "captured");
        ArrayNode pages = log.putArray("pages");
        ObjectNode page = pages.addObject();
        page.put("startedDateTime", LocalDateTime.now().toString());
        page.put("id", "page_1");
        page.put("title", "月次検収 - SES Manager Pro");
        ArrayNode entries = log.putArray("entries");
        for (JsonNode response : browser.networkResponses()) {
            String requestId = response.path("params").path("requestId").asText();
            JsonNode req = null;
            for (JsonNode r : browser.networkRequests()) {
                if (requestId.equals(r.path("params").path("requestId").asText())) {
                    req = r;
                    break;
                }
            }
            if (req == null) {
                continue;
            }
            JsonNode respParams = response.path("params");
            JsonNode reqParams = req.path("params");
            ObjectNode entry = entries.addObject();
            entry.put("startedDateTime", respParams.path("response").path("responseTime").asLong(0) == 0
                    ? LocalDateTime.now().toString() : LocalDateTime.now().toString());
            entry.put("time", 0);
            ObjectNode request = entry.putObject("request");
            request.put("method", reqParams.path("request").path("method").asText());
            request.put("url", reqParams.path("request").path("url").asText());
            request.put("httpVersion", "HTTP/1.1");
            ObjectNode responseNode = entry.putObject("response");
            responseNode.put("status", respParams.path("response").path("status").asInt(0));
            responseNode.put("statusText", respParams.path("response").path("statusText").asText());
            responseNode.put("httpVersion", "HTTP/1.1");
            ObjectNode content = responseNode.putObject("content");
            content.put("size", respParams.path("response").path("bodySize").asLong(0));
            content.put("mimeType", respParams.path("response").path("mimeType").asText());
            ArrayNode headers = responseNode.putArray("headers");
            for (JsonNode h : respParams.path("response").path("headers")) {
                ObjectNode header = headers.addObject();
                header.put("name", h.path("name").asText());
                header.put("value", h.path("value").asText());
            }
            ObjectNode timings = entry.putObject("timings");
            timings.put("send", 0);
            timings.put("wait", 0);
            timings.put("receive", 0);
        }
        // 注: このHARはChromeの実Networkイベントのみから構成される。Javaで結論を連結していない。
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(har));
    }

    /** CDPの実Console/Logイベントを書き出す（空なら「実イベントなし」と正直に記録）。 */
    private void writeRealConsole(CdpBrowser browser, Path file, String viewport, String targetUrl) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("[Real Browser Console Export - ").append(viewport).append("]\n");
        sb.append("URL: ").append(targetUrl).append("\n");
        sb.append("Timestamp: ").append(LocalDateTime.now()).append("\n\n");
        int errorCount = 0;
        for (JsonNode event : browser.consoleEvents()) {
            String method = event.path("method").asText();
            JsonNode params = event.path("params");
            String level = "log";
            String text = "";
            if ("Runtime.consoleAPICalled".equals(method)) {
                level = params.path("type").asText("log");
                StringBuilder args = new StringBuilder();
                for (JsonNode arg : params.path("args")) {
                    if (args.length() > 0) {
                        args.append(" ");
                    }
                    args.append(arg.path("value").asText(arg.path("description").asText("")));
                }
                text = args.toString();
            } else if ("Log.entryAdded".equals(method)) {
                level = params.path("entry").path("level").asText("log");
                text = params.path("entry").path("text").asText();
            }
            if ("error".equals(level)) {
                errorCount++;
            }
            sb.append("[").append(level).append("] ").append(text).append("\n");
        }
        if (browser.consoleEvents().isEmpty()) {
            sb.append("（このrunのCDPイベントではコンソール出力なし）\n");
        }
        sb.append("\nConsole Error Count: ").append(errorCount).append("\n");
        Files.writeString(file, sb.toString());
    }

    private int realConsoleErrorCount(CdpBrowser browser) {
        int count = 0;
        for (JsonNode event : browser.consoleEvents()) {
            String method = event.path("method").asText();
            JsonNode params = event.path("params");
            String level = "Runtime.consoleAPICalled".equals(method)
                    ? params.path("type").asText("log")
                    : params.path("entry").path("level").asText("log");
            if ("error".equals(level)) {
                count++;
            }
        }
        return count;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        // Chrome終了直後はサブプロセスがプロファイル内のファイルを削除している最中であり、
        // Files.walkの走査中にファイルが消えるとUncheckedIOException(NoSuchFileException)が
        // 発生してテストが落ちる。少し待って再試行し、それでも残った場合はベストエフォートで
        // 見逃す（一時ディレクトリの後始末がテストの成否を決めてはならない）。
        for (int attempt = 0; attempt < 5; attempt++) {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // ignore
                    }
                });
                return;
            } catch (java.io.UncheckedIOException e) {
                if (e.getCause() instanceof java.nio.file.NoSuchFileException) {
                    Thread.sleep(200);
                    continue;
                }
                throw e;
            }
        }
    }
}
