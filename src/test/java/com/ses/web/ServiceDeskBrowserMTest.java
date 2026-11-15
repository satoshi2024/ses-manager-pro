package com.ses.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.entity.Customer;
import com.ses.mapper.CustomerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF02の実起票・公開返信・内部メモをdesktopと390pxの実Chromeで検証する。 */
@Tag("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:service-desk-browser-m;DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceDeskBrowserMTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ServiceDeskBrowserMTest.class);
    @LocalServerPort private int port;
    @Autowired private CustomerMapper customers;
    private Long customerId;

    @BeforeEach
    void createCustomer() {
        Customer row = new Customer();
        row.setTenantId("default");
        row.setCompanyName("サービスデスク表示検証-" + UUID.randomUUID());
        customers.insert(row);
        customerId = row.getId();
    }

    @Test
    void desktopと幅390pxで起票と返信と内部メモを実送信できconsoleエラーが無い() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが必要です");
        Path evidence = Path.of("target", "browser-m-evidence", "service-desk");
        Files.createDirectories(evidence);
        String runId = "service-desk-" + UUID.randomUUID();
        Files.writeString(evidence.resolve("run-id.txt"), runId + "\n");
        ObjectNode summary = JSON.createObjectNode().put("runId", runId);
        try {
            runViewport(chrome, evidence, summary, "desktop", 1440, 1000, false);
            runViewport(chrome, evidence, summary, "mobile390", 390, 844, true);
        } finally {
            Files.writeString(evidence.resolve("summary.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        }
    }

    private void runViewport(Path chrome, Path evidence, ObjectNode summary, String viewport,
                             int width, int height, boolean mobile) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-service-desk-").toAbsolutePath().normalize();
        ObjectNode result = summary.putObject(viewport).put("width", width).put("height", height).put("mobile", mobile);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            browser.setDeviceMetrics(width, height, mobile, 1.0);
            try {
                browser.navigate("http://localhost:" + port + "/login");
                waitFor(browser, "document.querySelector('input[name=username]') !== null");
                browser.evaluate("document.querySelector('input[name=username]').value='admin';"
                        + "document.querySelector('input[name=password]').value='admin123';document.querySelector('form').submit();");
                waitFor(browser, "location.pathname !== '/login'");
                browser.navigate("http://localhost:" + port + "/service-desk/requests");
                waitFor(browser, "document.readyState === 'complete' && document.querySelector('#requestsTable') !== null"
                        + " && typeof bootstrap !== 'undefined' && document.querySelector('.filter-toggle') !== null");
                assertTrue(browser.evaluateStrict("innerWidth === " + width).asBoolean(false), "指定viewportを使用する");
                assertTrue(browser.evaluateStrict("document.querySelector('meta[name=viewport]').content.includes('width=device-width')").asBoolean(false));
                if (mobile) {
                    browser.evaluateStrict("document.querySelector('.filter-toggle').click();true");
                    waitFor(browser, "document.querySelector('.filter-toggle').getAttribute('aria-expanded') === 'true'");
                }
                assertControlsFit(browser, "#keyword,#statusFilter,#priorityFilter,#categoryFilter,#resetBtn");
                assertTrue(browser.evaluateStrict("!!document.querySelector('#requestsTable').closest('.table-responsive')").asBoolean(false));
                assertNoOverflow(browser);
                Files.write(evidence.resolve(viewport + "-list.png"), browser.screenshot());

                browser.evaluateStrict("document.querySelector('[data-bs-target=\"#newRequestModal\"]').click();true");
                waitFor(browser, "document.querySelector('#newRequestModal').classList.contains('show')"
                        + " && document.querySelector('#newRequestModal .modal-dialog').getAnimations().length === 0");
                waitFor(browser, "document.querySelector('#newCustomerId option[value=\"" + customerId + "\"]') !== null");
                assertControlsFit(browser, "#newCustomerId,#newChannel,#newCategory,#newPriority,#newSubject,#newDescription,#saveRequestBtn");
                String subject = "表示検証-" + viewport + "-" + UUID.randomUUID();
                browser.evaluateStrict("document.querySelector('#newCustomerId').value=" + quote(customerId.toString()) + ";"
                        + "document.querySelector('#newSubject').value=" + quote(subject) + ";"
                        + "document.querySelector('#newDescription').value='実送信による表示検証';");
                Files.write(evidence.resolve(viewport + "-create.png"), browser.screenshot());
                browser.evaluateStrict("document.querySelector('#newRequestForm').requestSubmit();true");
                waitFor(browser, "!document.querySelector('#newRequestModal').classList.contains('show')"
                        + " && document.querySelector('#requestsTableBody').textContent.includes(" + quote(subject) + ")");
                String href = browser.evaluateStrict("Array.from(document.querySelectorAll('#requestsTableBody tr'))"
                        + ".find(function(row){return row.textContent.includes(" + quote(subject) + ");}).querySelector('a[href]').getAttribute('href')").asText("");
                assertTrue(href.matches("/service-desk/requests/\\d+"), "作成した問い合わせの詳細リンクが必要");
                result.put("detailPath", href);
                browser.navigate("http://localhost:" + port + href);
                waitFor(browser, "document.querySelector('#subjectHeader') !== null && document.querySelector('#subjectHeader').textContent === " + quote(subject));
                assertControlsFit(browser, "#commentTextInput,#submitCommentBtn,#visPortal,#visInternal");
                postComment(browser, "visInternal", "内部メモ-" + viewport, "comment-internal");
                postComment(browser, "visPortal", "公開返信-" + viewport, "comment-portal");
                assertNoOverflow(browser);
                Files.write(evidence.resolve(viewport + "-detail.png"), browser.screenshot());
                result.put("created", true).put("internalMemoSent", true).put("replySent", true);
            } finally {
                Files.writeString(evidence.resolve(viewport + "-console.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(browser.consoleEvents()));
                long errors = browser.consoleEvents().stream().filter(this::isConsoleError).count();
                long apiFailures = browser.networkResponses().stream().filter(event -> {
                    JsonNode response = event.path("params").path("response");
                    return response.path("url").asText().contains("/api/") && response.path("status").asInt() >= 400;
                }).count();
                result.put("consoleErrors", errors).put("apiFailures", apiFailures).put("finalUrl", browser.currentUrl());
                assertTrue(errors == 0, "console errorは0件: " + errors);
                assertTrue(apiFailures == 0, "失敗したAPI応答は0件: " + apiFailures);
            }
        } finally {
            deleteProfile(profile);
        }
    }

    private void postComment(CdpBrowser browser, String radioId, String text, String cssClass) throws Exception {
        browser.evaluateStrict("document.getElementById(" + quote(radioId) + ").click();document.querySelector('#commentTextInput').value="
                + quote(text) + ";document.querySelector('#commentForm').requestSubmit();true");
        waitFor(browser, "Array.from(document.querySelectorAll('#commentsContainer ." + cssClass + "'))"
                + ".some(function(el){return el.textContent.includes(" + quote(text) + ");})");
    }

    private boolean isConsoleError(JsonNode event) {
        return "error".equals(event.path("params").path("type").asText())
                || "error".equals(event.path("params").path("entry").path("level").asText());
    }

    private void waitFor(CdpBrowser browser, String expression) throws Exception {
        assertTrue(browser.waitFor(expression, Duration.ofSeconds(30)), expression);
    }

    private void assertNoOverflow(CdpBrowser browser) throws Exception {
        assertTrue(browser.evaluateStrict("document.documentElement.scrollWidth <= innerWidth").asBoolean(false), "ページ全体の横溢れが無い");
    }

    private void assertControlsFit(CdpBrowser browser, String selectors) throws Exception {
        String quoted = quote(selectors);
        assertTrue(browser.evaluateStrict("Array.from(document.querySelectorAll(" + quoted + ")).length === "
                + selectors.split(",").length + " && Array.from(document.querySelectorAll(" + quoted + ")).every(function(el){"
                + "var r=el.getBoundingClientRect();return r.width>0 && r.left>=0 && r.right<=innerWidth+1;})")
                .asBoolean(false), "操作部品がviewport内に収まる: " + selectors);
    }

    private String quote(String value) throws Exception {
        return JSON.writeValueAsString(value);
    }

    private void deleteProfile(Path profile) {
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!profile.startsWith(temp) || !Files.exists(profile)) return;
        try (var paths = Files.walk(profile)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception failure) {
                    LOG.debug("一時Chrome profileの削除を後回しにします: {}", path);
                }
            });
        } catch (Exception failure) {
            LOG.debug("一時Chrome profileの走査を後回しにします: {}", profile);
        }
    }
}
