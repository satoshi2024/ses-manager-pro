package com.ses.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF02の一覧・検索・起票モーダルを390pxの実Chromeで検証する。 */
@Tag("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:service-desk-browser-m;DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "app.features.nf02.enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceDeskBrowserMTest {
    @LocalServerPort private int port;

    @Test
    void 幅390pxで一覧検索と起票フォームが画面内に収まり横スクロールは表の内側に限定される() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが必要です");
        Path profile = Files.createTempDirectory("chrome-profile-service-desk-").toAbsolutePath().normalize();
        Path evidence = Path.of("target", "browser-m-evidence", "service-desk");
        Files.createDirectories(evidence);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, 390, 844)) {
            browser.navigate("http://localhost:" + port + "/login");
            assertTrue(browser.waitFor("document.querySelector('input[name=username]') !== null", Duration.ofSeconds(30)));
            browser.evaluate("document.querySelector('input[name=username]').value='admin';"
                    + "document.querySelector('input[name=password]').value='admin123';document.querySelector('form').submit();");
            assertTrue(browser.waitFor("location.pathname !== '/login'", Duration.ofSeconds(30)));
            browser.navigate("http://localhost:" + port + "/service-desk");
            assertTrue(browser.waitFor("document.readyState === 'complete' && document.querySelector('#requestsTable') !== null"
                    + " && typeof bootstrap !== 'undefined' && document.querySelector('.filter-toggle') !== null", Duration.ofSeconds(30)));
            assertTrue(browser.evaluate("innerWidth === 390 && document.querySelector('meta[name=viewport]').content.includes('width=device-width')")
                    .asBoolean(false), "390pxのviewportを使用する");
            assertTrue(browser.evaluate("document.documentElement.scrollWidth <= innerWidth").asBoolean(false),
                    "一覧ページ全体に横方向のはみ出しがない");
            assertTrue(browser.evaluate("!!document.querySelector('#requestsTable').closest('.table-responsive')").asBoolean(false),
                    "一覧表は表専用のスクロール領域に収める");
            browser.evaluate("document.querySelector('.filter-toggle').click()");
            assertTrue(browser.waitFor("document.querySelector('.filter-toggle').getAttribute('aria-expanded') === 'true'"
                    + " && document.querySelector('#keyword').getBoundingClientRect().width > 0", Duration.ofSeconds(10)));
            assertControlsFit(browser, "#keyword,#statusFilter,#priorityFilter,#categoryFilter,#resetBtn");
            Files.write(evidence.resolve("mobile390-list.png"), browser.screenshot());

            browser.evaluate("document.querySelector('[data-bs-target=\"#newRequestModal\"]').click()");
            assertTrue(browser.waitFor("document.querySelector('#newRequestModal').classList.contains('show')"
                    + " && document.querySelector('#newRequestModal .modal-dialog').getAnimations().length === 0", Duration.ofSeconds(10)));
            assertControlsFit(browser, "#newCustomerId,#newChannel,#newCategory,#newPriority,#newSubject,#newDescription,#saveRequestBtn");
            assertTrue(browser.evaluate("document.querySelector('#newRequestModal').scrollWidth <= innerWidth").asBoolean(false),
                    "起票モーダルに横方向のはみ出しがない");
            browser.evaluate("document.querySelector('#newSubject').value='表示確認';document.querySelector('#newDescription').value='送信しない表示確認';");
            Files.write(evidence.resolve("mobile390-create.png"), browser.screenshot());
            browser.evaluate("bootstrap.Modal.getInstance(document.querySelector('#newRequestModal')).hide()");
            assertTrue(browser.waitFor("!document.querySelector('#newRequestModal').classList.contains('show')", Duration.ofSeconds(10)));
        } finally {
            // このテストで作成した一時profileだけを削除する。
            assertTrue(profile.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()));
            try (var paths = Files.walk(profile)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void assertControlsFit(CdpBrowser browser, String selectors) throws Exception {
        String quoted = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(selectors);
        assertTrue(browser.evaluate("Array.from(document.querySelectorAll(" + quoted + ")).length === "
                + selectors.split(",").length + " && Array.from(document.querySelectorAll(" + quoted + ")).every(function(el){"
                + "var r=el.getBoundingClientRect();return r.width>0 && r.left>=0 && r.right<=innerWidth+1;})")
                .asBoolean(false), "操作部品が390pxの表示領域内に収まる: " + selectors);
    }
}
