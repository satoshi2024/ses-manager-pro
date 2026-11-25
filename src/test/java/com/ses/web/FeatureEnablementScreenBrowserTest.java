package com.ses.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FE-E05: 画面「機能の開放」（{@code /feature-enablement}）を実 Chrome（CDP）で一通り操作して確かめる。
 *
 * <ul>
 *   <li>開放単位一覧（13 件、初期状態 CLOSED、実 Tomcat 上で対象機能 URL は 404）</li>
 *   <li>管理者 A による署名モーダルからの PDF 登録（登録者本人には確認ボタンが出ず取下げボタンだけ、一覧に署名者氏名を出さない）</li>
 *   <li>別の管理者 B による署名の確認（{@code CONFIRMED}）</li>
 *   <li>管理者 A による開放申請（申請者本人には承認ボタンが出ず取下げボタンだけ）</li>
 *   <li>別の管理者 B による開放承認（{@code OPEN}・実効値「開」・実 Tomcat 上で対象機能 URL の遮断が外れる）</li>
 *   <li>管理者 B による手動閉鎖（{@code MANUALLY_CLOSED}・理由 {@code MANUAL_VERIFICATION}・再び 404・履歴タブへの記録）</li>
 *   <li>375px 幅（スマートフォン相当）での履歴タブ {@code #searchForm} の折りたたみ（{@code SES.filterPanel}）と横はみ出し 0・console error 0</li>
 * </ul>
 */
@Tag("browser")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FeatureEnablementScreenBrowserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_A = "fe_browser_admin_a";
    private static final String ADMIN_B = "fe_browser_admin_b";
    private static final String PASSWORD_A = "FeBrowserPassA123";
    private static final String PASSWORD_B = "FeBrowserPassB123";
    private static final String DB_NAME = "fe_browser_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path UPLOAD_DIR = createTempUploadDir();

    @DynamicPropertySource
    static void registerIsolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:" + DB_NAME + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
        registry.add("app.upload.base-path", () -> UPLOAD_DIR.toAbsolutePath().toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpUsers() {
        cleanFeatureEnablementTables();
        ensureAdminUser(ADMIN_A, PASSWORD_A, "開放申請管理者A");
        ensureAdminUser(ADMIN_B, PASSWORD_B, "開放承認管理者B");
    }

    @AfterEach
    void cleanUp() {
        cleanFeatureEnablementTables();
    }

    @Test
    @DisplayName("FE-E05: 機能の開放画面で署名登録→別管理者確認→開放申請→別管理者承認→閉鎖→履歴・375px折りたたみを検証しconsole errorが0件")
    void signOffOpenApproveCloseAndMobileFilterCollapse() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");
        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of("target", "browser-m-evidence", "feature-enablement");
        Files.createDirectories(evidenceDir);

        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        assertEquals(404, getStatus(http, baseUrl + "/certification-learning-skill-gap"),
                "初期状態（CLOSED）では NF03 の画面は FeatureSwitchFilter により 404");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        String signedOn = today.minusDays(1).toString();
        String expiresOn = today.plusDays(180).toString();

        Path profileA = Files.createTempDirectory("chrome-profile-fe-admin-a-");
        Path profileB = Files.createTempDirectory("chrome-profile-fe-admin-b-");
        Path profileMobile = Files.createTempDirectory("chrome-profile-fe-mobile-");
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("baseUrl", baseUrl);

        try (CdpBrowser browserA = CdpBrowser.launch(chrome, profileA, 1440, 900);
             CdpBrowser browserB = CdpBrowser.launch(chrome, profileB, 1440, 900)) {

            login(browserA, baseUrl, ADMIN_A, PASSWORD_A, "adminA");
            login(browserB, baseUrl, ADMIN_B, PASSWORD_B, "adminB");

            // 1. 管理者 A が一覧を開き、全 13 開放単位が CLOSED で並ぶことを確認する
            browserA.navigate(baseUrl + "/feature-enablement");
            assertTrue(browserA.waitForStrict(
                    "document.readyState === 'complete'"
                            + " && document.querySelectorAll('#featureEnablementUnits tr').length === 13"
                            + " && document.getElementById('featureEnablementPropertyAlert').classList.contains('d-none')"
                            + " && document.getElementById('featureEnablementMasterAlert').classList.contains('d-none')",
                    Duration.ofSeconds(30)), "[adminA] 開放単位 13 件が DB 管理で表示される");
            Files.write(evidenceDir.resolve("01-units-initial.png"), browserA.screenshot());

            // 2. 管理者 A が「署名・受理」タブから NF03_BUSINESS_OWNER の署名 PDF を登録する
            browserA.evaluateStrict("document.getElementById('fe-signoffs-tab').click(); true");
            assertTrue(browserA.waitForStrict(
                    "document.getElementById('fe-signoffs-pane').classList.contains('active')",
                    Duration.ofSeconds(15)), "[adminA] 署名タブへ切り替わる");
            browserA.evaluateStrict("document.getElementById('featureEnablementRegisterSignOff').click(); true");
            assertTrue(browserA.waitForStrict(
                    "document.getElementById('featureEnablementModal').classList.contains('show')"
                            + " && document.querySelector('#feSignOffKind option[value=\"NF03_BUSINESS_OWNER\"]') !== null",
                    Duration.ofSeconds(15)), "[adminA] 署名登録モーダルが開く");

            String registeredPrep = browserA.evaluateStrict("(function(){"
                    + "var kind=document.getElementById('feSignOffKind');"
                    + "kind.value='NF03_BUSINESS_OWNER';"
                    + "kind.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "document.getElementById('feSignOffRole').value='BUSINESS_OWNER';"
                    + "document.getElementById('feSignOffName').value='山田 太郎';"
                    + "document.getElementById('feSignOffReference').value='BO-NF03-2026-01';"
                    + "document.getElementById('feSignOffSignedOn').value='" + signedOn + "';"
                    + "document.getElementById('feSignOffExpiresOn').value='" + expiresOn + "';"
                    + "var dt=new DataTransfer();"
                    + "var pdf='%PDF-1.4\\n1 0 obj\\n<< /Type /Catalog >>\\nendobj\\ntrailer\\n<< /Root 1 0 R >>\\n%%EOF\\n';"
                    + "dt.items.add(new File([pdf],'nf03-owner.pdf',{type:'application/pdf'}));"
                    + "var fileInput=document.getElementById('feSignOffFile');"
                    + "fileInput.files=dt.files;"
                    + "document.querySelector('#featureEnablementSignOffForm button[type=\"submit\"]').click();"
                    + "return fileInput.files.length===1?'OK':'NO_FILE';"
                    + "})()").asText("");
            assertEquals("OK", registeredPrep, "[adminA] PDF を添付して署名を送信する");

            assertTrue(browserA.waitForStrict(
                    "!document.getElementById('featureEnablementModal').classList.contains('show')"
                            + " && document.getElementById('featureEnablementSignOffs').textContent.indexOf('NF03_BUSINESS_OWNER') !== -1",
                    Duration.ofSeconds(30)), "[adminA] 登録した署名が一覧に表示される");
            assertFalse(browserA.evaluateStrict(
                    "document.getElementById('featureEnablementSignOffs').textContent.indexOf('山田 太郎') !== -1")
                    .asBoolean(true), "[adminA] 署名一覧には署名者の氏名を表示しない");
            assertTrue(browserA.evaluateStrict(
                    "document.querySelector('#featureEnablementSignOffs [data-fe-action=\"signOffWithdraw\"]') !== null"
                            + " && document.querySelector('#featureEnablementSignOffs [data-fe-action=\"signOffConfirm\"]') === null")
                    .asBoolean(false), "[adminA] 登録者本人には取下げボタンだけが出て確認ボタンは出ない");

            // 3. 別の管理者 B が署名を確認する（CONFIRMED）
            browserB.navigate(baseUrl + "/feature-enablement");
            assertTrue(browserB.waitForStrict(
                    "document.querySelectorAll('#featureEnablementUnits tr').length === 13",
                    Duration.ofSeconds(30)), "[adminB] 開放単位一覧が読み込まれる");
            browserB.evaluateStrict("document.getElementById('fe-signoffs-tab').click(); true");
            assertTrue(browserB.waitForStrict(
                    "document.querySelector('#featureEnablementSignOffs [data-fe-action=\"signOffConfirm\"]') !== null",
                    Duration.ofSeconds(15)), "[adminB] 別の管理者には確認ボタンが表示される");
            browserB.evaluateStrict(
                    "document.querySelector('#featureEnablementSignOffs [data-fe-action=\"signOffConfirm\"]').click(); true");
            confirmSwal(browserB, null, null, "adminB signOffConfirm");
            assertTrue(browserB.waitForStrict(
                    "document.querySelector('#featureEnablementSignOffs .badge.bg-success') !== null"
                            + " && document.querySelector('#featureEnablementSignOffs [data-fe-action=\"signOffRevoke\"]') !== null",
                    Duration.ofSeconds(30)), "[adminB] 署名が CONFIRMED になる");
            Files.write(evidenceDir.resolve("02-signoff-confirmed.png"), browserB.screenshot());

            // 4. 管理者 A が NF03 の開放を申請し、自分に承認ボタンが出ないことを確かめる
            browserA.evaluateStrict("document.getElementById('fe-units-tab').click();"
                    + "document.getElementById('featureEnablementReload').click(); true");
            assertTrue(browserA.waitForStrict(
                    "document.getElementById('fe-units-pane').classList.contains('active')"
                            + " && document.querySelector('#featureEnablementUnits [data-fe-action=\"request\"][data-key=\"NF03\"]') !== null",
                    Duration.ofSeconds(20)), "[adminA] NF03 の開放申請ボタンが表示される");
            browserA.evaluateStrict(
                    "document.querySelector('#featureEnablementUnits [data-fe-action=\"request\"][data-key=\"NF03\"]').click(); true");
            confirmSwal(browserA, ".swal2-textarea", "ブラウザ試験でのNF03開放申請", "adminA requestOpen");
            assertTrue(browserA.waitForStrict(
                    "document.querySelector('#featureEnablementUnits [data-fe-action=\"request\"][data-key=\"NF03\"]') === null",
                    Duration.ofSeconds(30)), "[adminA] 申請後は NF03 が申請中になる");

            browserA.evaluateStrict("document.getElementById('fe-requests-tab').click(); true");
            assertTrue(browserA.waitForStrict(
                    "document.getElementById('fe-requests-pane').classList.contains('active')"
                            + " && document.getElementById('featureEnablementRequests').textContent.indexOf('NF03') !== -1",
                    Duration.ofSeconds(15)), "[adminA] 申請タブに NF03 の申請が表示される");
            assertTrue(browserA.evaluateStrict(
                    "document.querySelector('#featureEnablementRequests [data-fe-action=\"withdraw\"]') !== null"
                            + " && document.querySelector('#featureEnablementRequests [data-fe-action=\"approve\"]') === null")
                    .asBoolean(false), "[adminA] 申請者本人には承認ボタンが出ず取下げボタンだけが出る");

            // 5. 別の管理者 B が申請を承認し、NF03 が即時に開くことを確かめる
            browserB.evaluateStrict("document.getElementById('fe-requests-tab').click();"
                    + "document.getElementById('featureEnablementReload').click(); true");
            assertTrue(browserB.waitForStrict(
                    "document.getElementById('fe-requests-pane').classList.contains('active')"
                            + " && document.querySelector('#featureEnablementRequests [data-fe-action=\"approve\"]') !== null",
                    Duration.ofSeconds(20)), "[adminB] 別の管理者には承認ボタンが表示される");
            browserB.evaluateStrict(
                    "document.querySelector('#featureEnablementRequests [data-fe-action=\"approve\"]').click(); true");
            confirmSwal(browserB, ".swal2-textarea", "署名確認済みに伴う承認", "adminB approve");

            browserB.evaluateStrict("document.getElementById('fe-units-tab').click(); true");
            assertTrue(browserB.waitForStrict(
                    "document.querySelector('#featureEnablementUnits [data-fe-action=\"close\"][data-key=\"NF03\"]') !== null",
                    Duration.ofSeconds(30)), "[adminB] 承認後 NF03 が OPEN になり閉じるボタンが表示される");
            Files.write(evidenceDir.resolve("03-unit-opened.png"), browserB.screenshot());

            assertEquals(302, getStatus(http, baseUrl + "/certification-learning-skill-gap"),
                    "NF03 開放後は未認証アクセスが 404 遮断ではなく Spring Security の 302（/login）へ到達する");

            // 6. 管理者 B が手動で NF03 を閉じ、即時に 404 へ戻ることと履歴タブの記録を確かめる
            browserB.evaluateStrict(
                    "document.querySelector('#featureEnablementUnits [data-fe-action=\"close\"][data-key=\"NF03\"]').click(); true");
            confirmSwal(browserB, ".swal2-select", "MANUAL_VERIFICATION", "adminB close");
            assertTrue(browserB.waitForStrict(
                    "document.querySelector('#featureEnablementUnits [data-fe-action=\"close\"][data-key=\"NF03\"]') === null"
                            + " && document.getElementById('featureEnablementUnits').textContent.indexOf('MANUAL_VERIFICATION') !== -1",
                    Duration.ofSeconds(30)), "[adminB] 手動閉鎖後 NF03 に MANUAL_VERIFICATION が表示される");

            assertEquals(404, getStatus(http, baseUrl + "/certification-learning-skill-gap"),
                    "NF03 手動閉鎖後は再び 404 に戻る");

            browserB.evaluateStrict("document.getElementById('fe-events-tab').click();"
                    + "document.getElementById('featureEnablementReload').click(); true");
            assertTrue(browserB.waitForStrict(
                    "document.getElementById('fe-events-pane').classList.contains('active')"
                            + " && document.getElementById('featureEnablementEvents').textContent.indexOf('NF03') !== -1"
                            + " && document.getElementById('featureEnablementEvents').textContent.indexOf('MANUALLY_CLOSED') !== -1",
                    Duration.ofSeconds(20)), "[adminB] 履歴タブに NF03 の閉鎖事象が表示される");
            Files.write(evidenceDir.resolve("04-events-history.png"), browserB.screenshot());

            recordSessionChecks(browserA, evidenceDir, summary, "desktopAdminA", 1440, 900);
            recordSessionChecks(browserB, evidenceDir, summary, "desktopAdminB", 1440, 900);
        } finally {
            deleteRecursively(profileA);
            deleteRecursively(profileB);
        }

        // 7. 375px（スマートフォン幅）で履歴タブの検索パネルが折りたたまれ、開閉できることを確かめる
        try (CdpBrowser mobile = CdpBrowser.launch(chrome, profileMobile, 375, 812)) {
            mobile.setDeviceMetrics(375, 812, true, 2.0);
            login(mobile, baseUrl, ADMIN_B, PASSWORD_B, "mobile375");
            mobile.navigate(baseUrl + "/feature-enablement");
            assertTrue(mobile.waitForStrict(
                    "document.readyState === 'complete'"
                            + " && document.querySelectorAll('#featureEnablementUnits tr').length === 13",
                    Duration.ofSeconds(30)), "[mobile375] 一覧が読み込まれる");
            mobile.evaluateStrict("document.getElementById('fe-events-tab').click(); true");
            assertTrue(mobile.waitForStrict(
                    "document.getElementById('fe-events-pane').classList.contains('active')",
                    Duration.ofSeconds(15)), "[mobile375] 履歴タブへ切り替わる");

            boolean collapsedInitially = mobile.evaluateStrict("(function(){"
                    + "var card=document.getElementById('searchForm').closest('.card');"
                    + "var body=document.getElementById('filterPanelBody');"
                    + "return card !== null && card.classList.contains('filter-card')"
                    + " && card.getAttribute('data-filter-collapsed') === 'true'"
                    + " && body !== null && window.getComputedStyle(body).display === 'none';"
                    + "})()").asBoolean(false);
            assertTrue(collapsedInitially, "[mobile375] 375px 幅では履歴検索フォーム #searchForm が初期状態で折りたたまれる");
            Files.write(evidenceDir.resolve("05-mobile375-filter-collapsed.png"), mobile.screenshot());

            mobile.evaluateStrict("document.querySelector('#fe-events-pane .filter-toggle').click(); true");
            assertTrue(mobile.waitForStrict("(function(){"
                    + "var card=document.getElementById('searchForm').closest('.card');"
                    + "var body=document.getElementById('filterPanelBody');"
                    + "return card.getAttribute('data-filter-collapsed') === 'false'"
                    + " && window.getComputedStyle(body).display !== 'none';"
                    + "})()", Duration.ofSeconds(10)), "[mobile375] 絞り込みトグルを押すと検索フォームが展開される");

            recordSessionChecks(mobile, evidenceDir, summary, "mobile375", 375, 812);
        } finally {
            deleteRecursively(profileMobile);
        }

        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    private static void confirmSwal(CdpBrowser browser, String inputSelector, String value, String step) throws Exception {
        String waitExpr = "document.querySelector('.swal2-popup .swal2-confirm') !== null"
                + (inputSelector != null ? " && document.querySelector('.swal2-popup " + inputSelector + "') !== null" : "");
        assertTrue(browser.waitForStrict(waitExpr, Duration.ofSeconds(15)), "[" + step + "] SweetAlert2 ダイアログが開く");
        if (inputSelector != null && value != null) {
            browser.evaluateStrict("(function(){"
                    + "var el=document.querySelector('.swal2-popup " + inputSelector + "');"
                    + "el.value='" + value + "';"
                    + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "return true;})()");
        }
        browser.evaluateStrict("document.querySelector('.swal2-popup .swal2-confirm').click(); true");
    }

    private static void login(CdpBrowser browser, String baseUrl, String username, String password, String label)
            throws Exception {
        browser.navigate(baseUrl + "/login");
        assertTrue(browser.waitFor(
                "document.querySelector('#username') !== null || document.querySelector('input[name=username]') !== null",
                Duration.ofSeconds(30)), "[" + label + "] login form");
        browser.evaluateStrict("(function(){"
                + "var u=document.querySelector('#username')||document.querySelector('input[name=username]');"
                + "var p=document.querySelector('#password')||document.querySelector('input[name=password]');"
                + "u.value='" + username + "';p.value='" + password + "';"
                + "document.querySelector('form').submit();"
                + "return true;})()");
        assertTrue(browser.waitFor("window.location.pathname !== '/login'", Duration.ofSeconds(30)),
                "[" + label + "] login redirect");
    }

    private static void recordSessionChecks(CdpBrowser browser, Path evidenceDir, ObjectNode summary,
                                            String name, int width, int height) throws Exception {
        boolean horizontalOverflow = browser.evaluateStrict(
                "document.documentElement.scrollWidth > window.innerWidth").asBoolean(true);
        long errors = browser.consoleEvents().stream().filter(FeatureEnablementScreenBrowserTest::isError).count();
        StringBuilder consoleLog = new StringBuilder();
        for (JsonNode event : browser.consoleEvents()) {
            consoleLog.append(event.toString()).append('\n');
        }
        Files.writeString(evidenceDir.resolve(name + "-console.txt"), consoleLog.toString());
        ObjectNode node = MAPPER.createObjectNode();
        node.put("width", width);
        node.put("height", height);
        node.put("consoleErrors", errors);
        node.put("horizontalOverflow", horizontalOverflow);
        summary.set(name, node);
        assertFalse(horizontalOverflow, "[" + name + "] 横方向の overflow が発生しないこと");
        assertEquals(0L, errors, "[" + name + "] ブラウザ console error が 0 件であること: " + consoleLog);
    }

    private static boolean isError(JsonNode event) {
        String type = event.path("params").path("type").asText("");
        String level = event.path("params").path("entry").path("level").asText("");
        return "error".equals(type) || "error".equals(level);
    }

    private static int getStatus(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void ensureAdminUser(String username, String password, String realName) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, username);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO sys_user (tenant_id, username, password, real_name, role, status, failed_count, "
                    + "password_change_required, deleted_flag) VALUES ('default', ?, ?, ?, '管理者', 1, 0, 0, 0)",
                    username, password, realName);
        }
    }

    private void cleanFeatureEnablementTables() {
        jdbc.update("DELETE FROM t_feature_sign_off_bundle_member");
        jdbc.update("DELETE FROM t_feature_sign_off_extension");
        jdbc.update("DELETE FROM t_feature_enablement_state");
        jdbc.update("DELETE FROM t_feature_enablement_setting");
        jdbc.update("DELETE FROM t_feature_sign_off_bundle");
        jdbc.update("DELETE FROM t_feature_sign_off");
        jdbc.update("DELETE FROM t_feature_enablement_request");
        jdbc.update("DELETE FROM t_feature_enablement_event");
        jdbc.update("DELETE FROM t_feature_enablement_generation");
    }

    private static Path createTempUploadDir() {
        try {
            return Files.createTempDirectory("fe-browser-uploads-");
        } catch (Exception e) {
            throw new IllegalStateException("一時 upload directory を作成できません", e);
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Windows での Chrome プロセス終了直後のロックは best effort で片付ける
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
    }
}
