package com.ses.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.ExternalMapping;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * S15 Stage B 会計・支払連携 Browser Demo (R4-P1-01 / tasks §R4-T08).
 * 実 Chrome (CDP・headless) で Desktop (1920x1080) と Mobile (390x844) の実測を行い、
 * DOM 検証・スクリーンショット・サマリーを evidence ディレクトリへ保存する。
 */
@org.junit.jupiter.api.Tag("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountingIntegrationBrowserDemoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private IntegrationConnectionService connectionService;

    @Autowired
    private ExternalMappingService mappingService;

    @Autowired
    private IntegrationJobService jobService;

    @Test
    @DisplayName("S15 Browser Demo: 会計連携画面（ジョブ/マッピング/接続/プレビュー/月次照合）実測")
    void captureAccountingScreensWithRealBrowser() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");

        // シードデータ準備
        IntegrationConnection conn = connectionService.getOrCreateConnection("default", 1L, "freee", "accounting");
        IntegrationTokensDto tokens = IntegrationTokensDto.builder()
                .accessToken("test-access-token-demo")
                .refreshToken("test-refresh-token-demo")
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

        IntegrationJob job = jobService.createJob(
                conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", 101L, "INV-DEMO-001", "hash-demo-101");
        jobService.claimJob(job.getId());
        jobService.markSucceeded(job.getId(), "DEAL-99001", "req-demo-01", "freee売上連携完了");

        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of(".kiro", "specs", "accounting-payment-integration", "evidence", "browser");
        Files.createDirectories(evidenceDir);
        String runId = "browser-demo-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.writeString(evidenceDir.resolve("run-id.txt"), runId + "\n");

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("runId", runId);
        summary.put("baseUrl", baseUrl);
        summary.put("connectionId", conn.getId());
        summary.put("jobId", job.getId());

        runViewport(chrome, baseUrl, evidenceDir, runId, "desktop", 1920, 1080, summary);
        runViewport(chrome, baseUrl, evidenceDir, runId, "mobile390", 390, 844, summary);

        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("Accounting Browser Demo done: " + evidenceDir + " (runId=" + runId + ")");
    }

    private void runViewport(Path chrome, String baseUrl, Path evidenceDir, String runId,
                             String viewport, int width, int height, ObjectNode summary) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-accounting-" + viewport);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            // 1. ログイン
            browser.navigate(baseUrl + "/login");
            waitFor(browser, "document.querySelector('#username') !== null || document.querySelector('input[name=username]') !== null");
            browser.evaluate("(function(){" +
                    "var u=document.querySelector('#username')||document.querySelector('input[name=username]');" +
                    "var p=document.querySelector('#password')||document.querySelector('input[name=password]');" +
                    "if(u) u.value='admin';" +
                    "if(p) p.value='admin123';" +
                    "var f=document.querySelector('form');" +
                    "if(f) f.submit();" +
                    "})()");

            // 2. 会計連携画面へ遷移
            Thread.sleep(1000);
            browser.navigate(baseUrl + "/accounting/integration");
            waitFor(browser, "document.querySelector('#accountingTabs') !== null");

            // Jobs タブ
            Path jobsPng = evidenceDir.resolve(viewport + "-01-jobs.png");
            Files.write(jobsPng, browser.screenshot());

            // Mappings タブ
            browser.evaluate("document.querySelector('#mappings-tab') && document.querySelector('#mappings-tab').click()");
            Thread.sleep(500);
            Path mappingsPng = evidenceDir.resolve(viewport + "-02-mappings.png");
            Files.write(mappingsPng, browser.screenshot());

            // Connections タブ
            browser.evaluate("document.querySelector('#connections-tab') && document.querySelector('#connections-tab').click()");
            Thread.sleep(500);
            Path connPng = evidenceDir.resolve(viewport + "-03-connections.png");
            Files.write(connPng, browser.screenshot());

            // Preview タブ
            browser.evaluate("document.querySelector('#preview-tab') && document.querySelector('#preview-tab').click()");
            Thread.sleep(500);
            Path previewPng = evidenceDir.resolve(viewport + "-04-preview.png");
            Files.write(previewPng, browser.screenshot());

            // Reconciliation タブ
            browser.evaluate("document.querySelector('#reconciliation-tab') && document.querySelector('#reconciliation-tab').click()");
            Thread.sleep(500);
            Path reconPng = evidenceDir.resolve(viewport + "-05-reconciliation.png");
            Files.write(reconPng, browser.screenshot());

            ObjectNode vpNode = MAPPER.createObjectNode();
            vpNode.put("jobsScreenshot", jobsPng.getFileName().toString());
            vpNode.put("mappingsScreenshot", mappingsPng.getFileName().toString());
            vpNode.put("connectionsScreenshot", connPng.getFileName().toString());
            vpNode.put("previewScreenshot", previewPng.getFileName().toString());
            vpNode.put("reconciliationScreenshot", reconPng.getFileName().toString());
            summary.set(viewport, vpNode);
        }
    }

    private static void waitFor(CdpBrowser browser, String jsCondition) throws Exception {
        long deadline = System.currentTimeMillis() + java.time.Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (browser.evaluate(jsCondition).asBoolean(false)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Timeout waiting for: " + jsCondition);
    }
}
