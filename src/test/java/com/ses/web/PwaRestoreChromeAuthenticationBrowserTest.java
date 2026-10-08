package com.ses.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 実ChromeとServlet認証を使い、PWA下書きの再認証・明示復元境界を検証する。 */
@Tag("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PwaRestoreChromeAuthenticationBrowserTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "chrome-restore-password";
    private static final String TENANT = "pwa-chrome-" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "pwa_chrome_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
        registry.add("app.security.oidc.tenant-id", () -> TENANT);
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    private Long userId;
    private Long engineerId;

    @AfterEach
    void cleanup() {
        if (userId == null) return;
        jdbc.update("DELETE FROM t_engineer_account_link WHERE sys_user_id = ?", userId);
        jdbc.update("DELETE FROM t_engineer WHERE id = ?", engineerId);
        jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
    }

    @Test
    void chromeLoginRequiresReauthenticationAndExplicitConfirmationBeforeShowingDraft() throws Exception {
        String username = "pwachrome" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password, role, status, failed_count, deleted_flag) "
                + "VALUES (?, ?, ?, '要員', 1, 0, 0)", TENANT, username, PASSWORD);
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbc.update("INSERT INTO t_engineer (tenant_id, full_name, employment_type, status, deleted_flag) "
                + "VALUES (?, ?, '正社員', '稼動中', 0)", TENANT, "PWA Chrome " + userId);
        engineerId = jdbc.queryForObject("SELECT id FROM t_engineer WHERE tenant_id = ? AND full_name = ?",
                Long.class, TENANT, "PWA Chrome " + userId);
        jdbc.update("INSERT INTO t_engineer_account_link (tenant_id, engineer_id, sys_user_id, linked_at) "
                + "VALUES (?, ?, ?, ?)", TENANT, engineerId, userId, LocalDateTime.now().minusDays(1));

        Path profile = Files.createTempDirectory("ses-nf04-auth-chrome-");
        CdpBrowser browser = null;
        try {
            browser = CdpBrowser.launch(CdpBrowser.chromeExecutable(), profile, 390, 844);
            String base = "http://localhost:" + port;
            browser.navigate(base + "/login");
            assertThat(browser.waitForStrict("!!document.querySelector('input[name=username]')", Duration.ofSeconds(15))).isTrue();
            browser.evaluate("document.querySelector('input[name=username]').value=" + JSON.writeValueAsString(username)
                    + ";document.querySelector('input[name=password]').value=" + JSON.writeValueAsString(PASSWORD)
                    + ";document.querySelector('form').requestSubmit();true");
            assertThat(browser.waitForStrict("!!(location.pathname==='/my/timesheet')", Duration.ofSeconds(20)))
                    .as("実フォームlogin後に要員ページへ遷移").isTrue();
            assertThat(browser.waitForStrict("!!(window.SES&&SES.pwaQueue&&SES.pwaQueue.getScope())", Duration.ofSeconds(20)))
                    .as("実session-contextでqueue scopeを確立").isTrue();
            assertThat(browser.waitForStrict("!!(navigator.serviceWorker&&navigator.serviceWorker.controller)", Duration.ofSeconds(20)))
                    .as("Service Worker controller確立").isTrue();

            browser.send("Network.emulateNetworkConditions", java.util.Map.of("offline", true, "latency", 0,
                    "downloadThroughput", 0, "uploadThroughput", 0, "connectionType", "none"));
            assertThat(browser.waitForStrict("!!(!navigator.onLine)", Duration.ofSeconds(10))).isTrue();
            JsonNode draft = browser.evaluateStrict("(async()=>{await SES.pwaQueue.whenReady();window.__draft="
                    + "await SES.pwaQueue.request({screen:'attendance',month:'2026-10',method:'POST',"
                    + "path:'/api/my/pwa/attendance/daily',baseVersion:0,resourceKey:'attendance:2026-10',"
                    + "payload:{workDate:'2026-10-07',clockIn:'09:00',clockOut:'18:00',breaks:[],"
                    + "breakMinutes:60,workType:'出勤',workplaceType:'客先',remarks:'chrome-confirm-only'}});"
                    + "const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
                    + "const rows=await new Promise((ok,no)=>{const q=db.transaction('queue').objectStore('queue').getAll();q.onsuccess=()=>ok(q.result);q.onerror=()=>no(q.error)});db.close();"
                    + "const stored=rows.find(x=>x.payload&&x.payload.remarks==='chrome-confirm-only');"
                    + "return JSON.stringify({id:stored&&stored.clientRequestId,scope:SES.pwaQueue.getScope()})})()");
            String requestId = JSON.readTree(draft.asText()).path("id").asText();
            assertThat(requestId).isNotBlank();
            browser.send("Network.setBlockedURLs", java.util.Map.of("urls", java.util.List.of(base + "/my/timesheet")));
            browser.navigate(base + "/my/timesheet");
            boolean offlineShellLoaded = browser.waitForStrict("!!document.title.includes('オフライン')", Duration.ofSeconds(15));
            assertThat(offlineShellLoaded).as("network-only navigationの失敗時にService Worker offline shellを表示。診断="
                    + browser.evaluateStrict("(async()=>JSON.stringify({title:document.title,href:location.href,online:navigator.onLine,controlled:!!navigator.serviceWorker.controller,offlineAssetCached:!!(await caches.match('/offline.html'))}))()").asText()).isTrue();
            browser.send("Network.setBlockedURLs", java.util.Map.of("urls", java.util.List.of()));
            assertThat(browser.evaluateStrict("document.getElementById('offline-drafts').hidden").asBoolean())
                    .as("通常のoffline表示では復元下書きはロック").isTrue();
            assertThat(browser.evaluateStrict("document.getElementById('offline-confirm-restore').hidden").asBoolean())
                    .as("再認証前は確認操作を表示しない").isTrue();

            browser.send("Network.emulateNetworkConditions", java.util.Map.of("offline", false, "latency", 0,
                    "downloadThroughput", -1, "uploadThroughput", -1, "connectionType", "wifi"));
            assertThat(browser.waitForStrict("!!navigator.onLine", Duration.ofSeconds(10))).isTrue();
            assertThat(browser.waitForStrict("!!(document.getElementById('offline-reauth') && !document.getElementById('offline-reauth').disabled)", Duration.ofSeconds(10))).isTrue();
            browser.evaluate("document.getElementById('offline-restore-password').value="
                    + JSON.writeValueAsString(PASSWORD) + ";document.getElementById('offline-reauth').click();true");
            assertThat(browser.waitForStrict("!!(document.getElementById('offline-confirm-restore') && !document.getElementById('offline-confirm-restore').hidden)", Duration.ofSeconds(15)))
                    .as("実Spring Security再認証後に確認操作を提示").isTrue();
            assertThat(browser.evaluateStrict("document.getElementById('offline-drafts').hidden").asBoolean())
                    .as("再認証だけでは本文を表示しない").isTrue();
            assertThat(browser.evaluateStrict("document.getElementById('offline-restore-password').value").asText()).isEmpty();
            assertThat(browser.evaluateStrict("Object.keys(localStorage).concat(Object.keys(sessionStorage)).filter(k=>/proof|password/i.test(k)).length")
                    .asInt()).isZero();

            // 確認後の自動flushだけを遮断し、実API認証・scope確認・明示復元を保ったままdraftを観察する。
            browser.evaluate("window.__nativeFetch=window.fetch.bind(window);window.fetch=(input,init)=>"
                    + "String(input).includes('/api/my/pwa/attendance/daily')?Promise.reject(new TypeError('offline test fence')):window.__nativeFetch(input,init);true");
            browser.evaluate("document.getElementById('offline-confirm-restore').click();true");
            assertThat(browser.waitForStrict("!!(document.getElementById('offline-drafts') && !document.getElementById('offline-drafts').hidden)", Duration.ofSeconds(15)))
                    .as("実reauthenticate/confirm API成功後、ユーザー確認操作で下書きを表示").isTrue();
            assertThat(browser.evaluateStrict("document.getElementById('offline-draft-list').textContent.includes('2026-10-07')").asBoolean())
                    .isTrue();
            assertThat(browser.evaluateStrict("document.getElementById('offline-draft-list').textContent.includes('chrome-confirm-only')").asBoolean())
                    .as("自由記述は復元UIへ表示しない").isFalse();
            assertThat(browser.evaluateStrict("(async()=>{const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});const rows=await new Promise(ok=>{const q=db.transaction('queue').objectStore('queue').getAll();q.onsuccess=()=>ok(q.result)});db.close();return rows.some(x=>x.clientRequestId==="
                    + JSON.writeValueAsString(requestId) + "&&x.status==='PENDING')})()").asBoolean()).isTrue();
        } finally {
            if (browser != null && !browser.closeGracefully(Duration.ofSeconds(10))) browser.close();
            try (var paths = Files.walk(profile)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
