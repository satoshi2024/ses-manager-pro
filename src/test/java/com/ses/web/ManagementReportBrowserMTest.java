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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-10 M: 管理レポート画面をdesktop/390pxで実Chrome検証する。 */
@Tag("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ManagementReportBrowserMTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("NF-10-M: 管理レポートをdesktop/390pxで実測しsnapshot表示契約を確認")
    void captureManagementReportScreensWithRealBrowser() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");
        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of("target", "browser-m-evidence", "scheduled-management-reporting");
        Files.createDirectories(evidenceDir);
        String runId = "browser-m-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.writeString(evidenceDir.resolve("run-id.txt"), runId + "\n");

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("runId", runId);
        summary.put("baseUrl", baseUrl);
        runViewport(chrome, baseUrl, evidenceDir, summary, "desktop", 1920, 1080);
        runViewport(chrome, baseUrl, evidenceDir, summary, "mobile390", 390, 844);
        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("NF-10 M browser evidence: " + evidenceDir);
    }

    private void runViewport(Path chrome, String baseUrl, Path evidenceDir,
                             ObjectNode summary, String viewport, int width, int height) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-management-report-" + viewport);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            browser.navigate(baseUrl + "/login");
            assertTrue(browser.waitFor(
                    "document.querySelector('#username') !== null || document.querySelector('input[name=username]') !== null",
                    Duration.ofSeconds(30)), "[" + viewport + "] login form");
            browser.evaluate("(function(){var u=document.querySelector('#username')||document.querySelector('input[name=username]');"
                    + "var p=document.querySelector('#password')||document.querySelector('input[name=password]');"
                    + "if(!u||!p)return false;u.value='admin';p.value='admin123';return true;})()");
            browser.evaluate("(function(){var f=document.querySelector('form');if(f)f.submit();return !!f;})()");
            assertTrue(browser.waitFor("window.location.pathname !== '/login'", Duration.ofSeconds(30)),
                    "[" + viewport + "] login redirect");

            browser.navigate(baseUrl + "/management-reports");
            assertTrue(browser.waitFor(
                    "document.readyState === 'complete' && document.querySelector('#managementReportApp') !== null",
                    Duration.ofSeconds(30)), "[" + viewport + "] management report page");
            assertTrue(browser.evaluate(
                    "document.querySelector('meta[name=viewport]').content.includes('width=device-width')").asBoolean(false),
                    "[" + viewport + "] viewport contract");
            assertTrue(browser.evaluate("document.querySelector('#reportPreviewBtn') !== null"
                    + " && document.querySelector('#runResult') !== null").asBoolean(false),
                    "[" + viewport + "] preview/run controls");

            byte[] png = browser.screenshot();
            Path pngPath = evidenceDir.resolve(viewport + "-management-report.png");
            Files.write(pngPath, png);
            ObjectNode viewportNode = MAPPER.createObjectNode();
            viewportNode.put("width", width);
            viewportNode.put("height", height);
            viewportNode.put("screenshot", pngPath.getFileName().toString());
            viewportNode.put("screenshotSha256", sha256(png));
            boolean horizontalOverflow = browser.evaluate(
                    "document.documentElement.scrollWidth > window.innerWidth").asBoolean(false);
            assertTrue(!horizontalOverflow, "[" + viewport + "] 横方向overflowが発生しないこと");
            viewportNode.put("horizontalOverflow", horizontalOverflow);
            viewportNode.put("finalUrl", browser.currentUrl());
            summary.set(viewport, viewportNode);
        } finally {
            deleteRecursively(profile);
        }
    }

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 一時profileの後片付けはbest effort。
                }
            });
        } catch (Exception ignored) {
            // 一時profileの後片付けはbest effort。
        }
    }
}
