package com.ses.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A（R25契約A・S10_TECHNICAL_ACCEPTANCE）: 派遣コンプライアンスG2 gate画面のbrowser目視。
 * 実Chrome（CDP・headless）でdesktop（1920x1080）と390px（390x844）で実測し、
 * DOM検証・スクリーンショット・コンソールイベントを `evidence/browser-g2/` へ保存する。
 * 要件（R25 A）: desktop/390px・console error 0。fixtureはTEST/DEVELOPMENT（実在登録なし・§6・R25 §2）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class G2GateBrowserPhaseATest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("PhaseA: 実ChromeでG2 gate画面（9 tabs）をdesktop/390pxで実測")
    void captureG2GateScreensWithRealBrowser() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");
        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of(".kiro", "specs", "dispatch-outsourcing-compliance-ledger", "evidence", "browser-g2");
        Files.createDirectories(evidenceDir);
        String runId = "browser-g2-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.writeString(evidenceDir.resolve("run-id.txt"), runId + "\n");

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("runId", runId);
        summary.put("baseUrl", baseUrl);

        runViewport(chrome, baseUrl, evidenceDir, runId, "desktop", 1920, 1080, summary);
        runViewport(chrome, baseUrl, evidenceDir, runId, "mobile390", 390, 844, summary);

        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("PhaseA G2 gate browser Demo done: " + evidenceDir + " (runId=" + runId + ")");
    }

    private void runViewport(Path chrome, String baseUrl, Path evidenceDir, String runId,
                             String viewport, int width, int height, ObjectNode summary) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-g2-" + viewport);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            // ---- 1. ログイン（admin/admin123・dev/testはNoOpエンコーダ） ----
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

            // ---- 2. /compliance-gate へ遷移 ----
            browser.navigate(baseUrl + "/compliance-gate");
            waitFor(browser, "document.getElementById('gateTabs') !== null");
            // 9 tabsすべて存在する
            String[] tabIds = {"tab-mapping", "tab-reviewer-type", "tab-policy", "tab-assignment",
                    "tab-approval", "tab-external-review", "tab-verification", "tab-active", "tab-event-history"};
            for (String tabId : tabIds) {
                boolean exists = browser.evaluate("document.getElementById('" + tabId + "') !== null").asBoolean();
                assertTrue(exists, "[" + viewport + "] tab " + tabId + " が存在する");
            }
            // capabilitiesが読み込まれ（server計算・JS role判定不使用・R25 A）
            waitFor(browser, "document.getElementById('gateCapabilityNote').textContent.indexOf('capabilities') >= 0");
            // Mapping tabの一覧が描画される
            waitFor(browser, "document.getElementById('mappingTableBody') !== null");

            // ---- 3. スクリーンショットとコンソールイベント保存 ----
            byte[] png = browser.screenshot();
            Path pngPath = evidenceDir.resolve(viewport + "-compliance-gate.png");
            Files.write(pngPath, png);
            summary.put(viewport + "-png", pngPath.getFileName().toString());
            summary.put(viewport + "-png-sha256", sha256(png));
            summary.put(viewport + "-finalUrl", browser.evaluate("window.location.href").asText(""));

            List<com.fasterxml.jackson.databind.JsonNode> console = browser.consoleEvents();
            StringBuilder consoleLog = new StringBuilder();
            for (var event : console) {
                consoleLog.append(event.toString()).append('\n');
            }
            Files.writeString(evidenceDir.resolve(viewport + "-console.txt"), consoleLog.toString());
            summary.put(viewport + "-consoleCount", console.size());
            // console error 0（R25 A要件）
            long errors = console.stream()
                    .filter(e -> e.path("type").asText("").contains("error"))
                    .count();
            assertTrue(errors == 0, "[" + viewport + "] console errorが0であること（errors=" + errors + "）");
        } finally {
            deleteRecursively(profile);
        }
    }

    private void waitFor(CdpBrowser browser, String jsExpression) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(40).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (browser.evaluate(jsExpression).asBoolean(false)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("waitFor timeout: " + jsExpression);
    }

    private String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
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
}
