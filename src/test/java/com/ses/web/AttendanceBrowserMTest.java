package com.ses.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * T074（M）browser Demo: 雇用勤怠管理画面（外部同期カード・客先工数差異カード）を
 * 実Chrome（CDP・headless）でdesktop（1920x1080）と390px（390x844）で実測し、
 * DOM検証・スクリーンショット・コンソールイベントを `evidence/browser-m/` へ保存する。
 *
 * <p>R2-P2-01（390px実ブラウザ）の再評価も兼ねる。ログイン→勤怠管理画面→同期カード/差異カードの
 * 表示を同一ブラウザセッションで検証する。</p>
 *
 * <p>Chrome依存のため既定のfast suiteへ無条件追加しない。専用profile（-Pbrowser-tests）とCI gateで実行する。
 */
@Tag("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AttendanceBrowserMTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("T074-M: 実Chromeで勤怠管理画面（同期カード・差異カード）をdesktop/390pxで実測")
    void captureAttendanceManagementScreensWithRealBrowser() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");
        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of(".kiro", "specs", "attendance-leave-overtime-compliance", "evidence", "browser-m");
        Files.createDirectories(evidenceDir);
        String runId = "browser-m-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.writeString(evidenceDir.resolve("run-id.txt"), runId + "\n");

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("runId", runId);
        summary.put("baseUrl", baseUrl);

        runViewport(chrome, baseUrl, evidenceDir, runId, "desktop", 1920, 1080, summary);
        runViewport(chrome, baseUrl, evidenceDir, runId, "mobile390", 390, 844, summary);

        Files.writeString(evidenceDir.resolve("summary.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("T074-M browser Demo done: " + evidenceDir + " (runId=" + runId + ")");
    }

    private void runViewport(Path chrome, String baseUrl, Path evidenceDir, String runId,
                             String viewport, int width, int height, ObjectNode summary) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-" + viewport);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            // ---- 1. ログイン（admin/admin123、dev/testはNoOpエンコーダ） ----
            browser.navigate(baseUrl + "/login");
            waitFor(browser, "document.querySelector('#username') !== null || document.querySelector('input[name=username]') !== null");
            browser.evaluate("(function(){" +
                    "var u=document.querySelector('#username')||document.querySelector('input[name=username]');" +
                    "var p=document.querySelector('#password')||document.querySelector('input[name=password]');" +
                    "if(!u||!p)return false;u.value='admin';p.value='admin123';return true;})()");
            browser.evaluate("(function(){var f=document.querySelector('form');if(f)f.submit();return !!f;})()");
            waitFor(browser, "window.location.pathname !== '/login'");
            String afterLogin = browser.evaluate("window.location.pathname").asText("");
            assertTrue(!afterLogin.equals("/login"), "[" + viewport + "] ログイン後に/loginから遷移すること（URL=" + afterLogin + ")");

            // ---- 2. 勤怠管理画面へ遷移 ----
            browser.navigate(baseUrl + "/work-record/attendance");
            waitFor(browser, "document.getElementById('attendanceManagementBody') !== null");
            // 同期カード・差異カードが描画される
            boolean syncCard = browser.evaluate(
                    "document.querySelector('[id=attendanceSyncProvider]') !== null || document.getElementById('syncRunPush') !== null").asBoolean();
            boolean discrepancyCard = browser.evaluate(
                    "document.getElementById('attendanceDiscrepancyTable') !== null").asBoolean();
            assertTrue(syncCard, "[" + viewport + "] 外部同期カードが表示される");
            assertTrue(discrepancyCard, "[" + viewport + "] 客先工数差異カードが表示される");

            // 同期カードのprovider状態が読み込まれる（status API）
            waitFor(browser, "document.getElementById('attendanceSyncProvider') !== null && document.getElementById('attendanceSyncProvider').textContent.indexOf('provider') >= 0");

            // ---- 3. スクリーンショットとコンソールイベント保存 ----
            byte[] png = browser.screenshot();
            Path pngPath = evidenceDir.resolve(viewport + "-attendance-management.png");
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
