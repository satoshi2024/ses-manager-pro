package com.ses.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.importcenter.metadata.ImportCatalogPage;
import com.ses.importcenter.metadata.ImportCatalogService;
import com.ses.importcenter.metadata.ImportCsvIngestionService;
import com.ses.importcenter.metadata.ImportMappingView;
import com.ses.importcenter.metadata.ImportMetadataService;
import com.ses.importcenter.metadata.ImportPreviewPage;
import com.ses.importcenter.metadata.ImportPreviewRow;
import com.ses.importcenter.metadata.ImportRawUploadHeaders;
import com.ses.importcenter.metadata.ImportSourceView;
import com.ses.importcenter.metadata.ImportUploadJobDetail;
import com.ses.importcenter.metadata.ImportValidationResultService;
import com.ses.importcenter.metadata.ImportValidationRunService;
import com.ses.importcenter.metadata.ImportValidationRunSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * NF-06 取込センター画面（W3-12）を desktop / 390px の実Chromeで確認する。
 *
 * <p>upload → mapping選択 → 検証（実行中は {@code GET /jobs/{id}} で状態を確認）→ preview を画面操作で一続きに行い、
 * console error が0件・横方向のoverflowが無いことを確認する。取込のservice層は {@code @MockBean} にし、合成の応答だけを
 * 返す（実際の取込・検証の正しさは service層のH2・MySQL testが見る）。controller・security chain・CSRF・JSは実物を使う。
 */
@Tag("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ImportCenterBrowserMTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SOURCE_ID = 5;
    private static final long MAPPING_ID = 9;
    private static final long JOB_ID = 77;
    private static final String CSV = "externalKey,companyName,contactPerson\\nC-1,合成会社一,山田太郎\\n"
            + "C-1,合成会社二,佐藤花子\\nC-3,,鈴木一郎\\n";

    @LocalServerPort
    private int port;

    @MockBean
    private ImportCsvIngestionService ingestion;
    @MockBean
    private ImportMetadataService metadata;
    @MockBean
    private ImportCatalogService catalog;
    @MockBean
    private ImportValidationRunService runs;
    @MockBean
    private ImportValidationResultService results;

    private final AtomicReference<String> jobState = new AtomicReference<>("UPLOADED");
    private final AtomicReference<ImportRawUploadHeaders> uploadedHeaders = new AtomicReference<>();

    @BeforeEach
    void stubServices() {
        jobState.set("UPLOADED");
        uploadedHeaders.set(null);
        when(catalog.listSources(anyLong(), anyLong())).thenReturn(new ImportCatalogPage<>(1, 200, 1,
                List.of(new ImportSourceView(SOURCE_ID, "legacy-crm", "合成の旧CRM", "ACTIVE", 1))));
        when(catalog.listMappings(eq(SOURCE_ID), anyLong(), anyLong())).thenReturn(new ImportCatalogPage<>(1, 200, 1,
                List.of(new ImportMappingView(MAPPING_ID, SOURCE_ID, "CUSTOMER", "ses-import-v1", 1, "0123456789ab",
                        Map.of("externalKey", "externalKey", "companyName", "companyName",
                                "contactPerson", "contactPerson")))));
        doAnswer(invocation -> {
            uploadedHeaders.set(invocation.getArgument(0));
            ImportCsvIngestionService.InputStreamSupplier body = invocation.getArgument(1);
            try (var input = body.open()) {
                input.readAllBytes();
            }
            return detail("UPLOADED");
        }).when(ingestion).upload(any(), any());
        when(metadata.getUploadedJobDetail(JOB_ID)).thenAnswer(invocation -> detail(jobState.get()));
        when(runs.validate(eq(JOB_ID), any())).thenAnswer(invocation -> {
            jobState.set("VALIDATING");
            // 実行中の状態確認（3秒周期のpolling）が少なくとも1回走る時間だけ待つ
            Thread.sleep(4_000);
            jobState.set("VALIDATED");
            return new ImportValidationRunSummary(JOB_ID, "VALIDATED", 501, 1, 3, 1, 2, 0);
        });
        when(results.preview(eq(JOB_ID), anyLong(), anyLong(), any())).thenReturn(new ImportPreviewPage(JOB_ID, 501,
                1, 50, 3, "ALL", List.of(
                        row(1, "ACCEPTED", List.of(), "C-1", "合成会社一", "山＊＊"),
                        row(2, "REJECTED", List.of("DUPLICATE_SOURCE_KEY"), "C-1", "合成会社二", "佐＊＊"),
                        row(3, "REJECTED", List.of("FIELD_REQUIRED"), "C-3", null, "鈴＊＊"))));
    }

    @Test
    @DisplayName("NF-06 W3-12: 取込センターをdesktop/390pxで upload→検証→preview まで操作しconsole errorが0件")
    void uploadValidateAndPreviewOnDesktopAndPhoneWidth() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");
        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of("target", "browser-m-evidence", "data-migration-import-center");
        Files.createDirectories(evidenceDir);
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("baseUrl", baseUrl);
        runViewport(chrome, baseUrl, evidenceDir, summary, "desktop", 1920, 1080);
        stubServices();
        runViewport(chrome, baseUrl, evidenceDir, summary, "mobile390", 390, 844);
        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    private void runViewport(Path chrome, String baseUrl, Path evidenceDir, ObjectNode summary, String viewport,
                             int width, int height) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-import-center-" + viewport);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            login(browser, baseUrl, viewport);

            browser.navigate(baseUrl + "/import-center");
            assertTrue(browser.waitFor("document.readyState === 'complete'"
                    + " && document.querySelector('#importUploadSource option[value=\"" + SOURCE_ID + "\"]') !== null",
                    Duration.ofSeconds(30)), "[" + viewport + "] 取込元の一覧が読み込まれる");

            String prepared = browser.evaluate("(function(){"
                    + "var input=document.getElementById('importUploadFile');"
                    + "var dt=new DataTransfer();"
                    + "dt.items.add(new File([\"" + CSV + "\"],'customer.csv',{type:'text/csv'}));"
                    + "input.files=dt.files;"
                    + "document.getElementById('importUploadSource').value='" + SOURCE_ID + "';"
                    + "document.getElementById('importUploadEncoding').value='UTF8';"
                    + "document.getElementById('importUploadBtn').click();"
                    + "return input.files.length===1?'OK':'NO_FILE';})()").asText("");
            assertEquals("OK", prepared, "[" + viewport + "] CSVを選んでuploadを押す");
            assertTrue(browser.waitFor("document.getElementById('importJobDetail').textContent.indexOf('#" + JOB_ID
                    + "') !== -1 && document.querySelector('#importValidationMapping option[value=\"" + MAPPING_ID
                    + "\"]') !== null", Duration.ofSeconds(30)), "[" + viewport + "] uploadしたjobとmapping版が表示される");
            ImportRawUploadHeaders headers = uploadedHeaders.get();
            assertNotNull(headers, "[" + viewport + "] upload APIへ到達する");
            assertEquals("legacy-crm", headers.sourceCode());

            browser.evaluate("(function(){document.getElementById('importValidationMapping').value='" + MAPPING_ID
                    + "';document.getElementById('importValidateBtn').click();return true;})()");
            assertTrue(browser.waitFor("document.querySelectorAll('#importPreviewBody tr').length === 3"
                    + " && document.querySelector('#importPreviewBody code') !== null"
                    + " && !document.getElementById('importSummarySection').classList.contains('d-none')",
                    Duration.ofSeconds(40)), "[" + viewport + "] 検証の要約とmask済みpreviewが表示される");
            assertTrue(browser.evaluate("document.getElementById('importPreviewBody').textContent.indexOf('山田') === -1")
                    .asBoolean(false), "[" + viewport + "] 原文の担当者名を表示しない");
            assertTrue(browser.evaluate("!document.getElementById('importErrorExportBtn').disabled").asBoolean(false),
                    "[" + viewport + "] 却下行があればエラーCSVを出力できる");

            boolean horizontalOverflow = browser.evaluate(
                    "document.documentElement.scrollWidth > window.innerWidth").asBoolean(true);
            assertTrue(!horizontalOverflow, "[" + viewport + "] 横方向のoverflowが発生しないこと");
            long errors = browser.consoleEvents().stream().filter(ImportCenterBrowserMTest::isError).count();
            StringBuilder consoleLog = new StringBuilder();
            for (JsonNode event : browser.consoleEvents()) {
                consoleLog.append(event.toString()).append('\n');
            }
            Files.writeString(evidenceDir.resolve(viewport + "-console.txt"), consoleLog.toString());
            Files.write(evidenceDir.resolve(viewport + "-import-center.png"), browser.screenshot());
            ObjectNode node = MAPPER.createObjectNode();
            node.put("width", width);
            node.put("height", height);
            node.put("consoleErrors", errors);
            node.put("horizontalOverflow", horizontalOverflow);
            summary.set(viewport, node);
            assertEquals(0, errors, "[" + viewport + "] ブラウザ console error が 0 であること");
        } finally {
            deleteRecursively(profile);
        }
    }

    private static void login(CdpBrowser browser, String baseUrl, String viewport) throws Exception {
        browser.navigate(baseUrl + "/login");
        assertTrue(browser.waitFor("document.querySelector('#username') !== null"
                + " || document.querySelector('input[name=username]') !== null", Duration.ofSeconds(30)),
                "[" + viewport + "] login form");
        browser.evaluate("(function(){var u=document.querySelector('#username')||document.querySelector('input[name=username]');"
                + "var p=document.querySelector('#password')||document.querySelector('input[name=password]');"
                + "if(!u||!p)return false;u.value='admin';p.value='admin123';return true;})()");
        browser.evaluate("(function(){var f=document.querySelector('form');if(f)f.submit();return !!f;})()");
        assertTrue(browser.waitFor("window.location.pathname !== '/login'", Duration.ofSeconds(30)),
                "[" + viewport + "] login redirect");
    }

    private static boolean isError(JsonNode event) {
        String type = event.path("params").path("type").asText("");
        String level = event.path("params").path("entry").path("level").asText("");
        return "error".equals(type) || "error".equals(level);
    }

    private static ImportUploadJobDetail detail(String state) {
        return new ImportUploadJobDetail(JOB_ID, state, "CUSTOMER", "abcdef012345", 1, "UTF8");
    }

    private static ImportPreviewRow row(long number, String disposition, List<String> codes, String externalKey,
                                        String companyName, String contactPerson) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("externalKey", externalKey);
        fields.put("companyName", companyName);
        fields.put("contactPerson", contactPerson);
        String reference = String.format("%064x", number * 7919);
        return new ImportPreviewRow(number, reference, disposition, codes, fields);
    }

    private static void deleteRecursively(Path directory) {
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
