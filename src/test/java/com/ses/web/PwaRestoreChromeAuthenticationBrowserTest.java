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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            OfflineNavigationProof offlineProof = proveWorkerOfflineNavigation(browser, base);
            assertThat(offlineProof.workerRequestFailed()).as("worker target内の実fetchがERR_INTERNET_DISCONNECTEDで失敗する").isTrue();
            assertThat(offlineProof.pageResponseFromWorker()).as("本navigationのmain-frame responseはService Worker由来").isTrue();
            assertThat(offlineProof.offlineShellLoaded()).as("失敗後にoffline shellを表示する").isTrue();
            assertThat(browser.evaluateStrict("document.getElementById('offline-drafts').hidden").asBoolean())
                    .as("通常のoffline表示では復元下書きはロック").isTrue();
            assertThat(browser.evaluateStrict("document.getElementById('offline-confirm-restore').hidden").asBoolean())
                    .as("再認証前は確認操作を表示しない").isTrue();

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
            assertThat(browser.evaluateStrict("window.__nativeFetch=window.fetch.bind(window);window.fetch=(input,init)=>"
                    + "String(input).includes('/api/my/pwa/attendance/daily')?Promise.reject(new TypeError('offline test fence')):window.__nativeFetch(input,init);true").asBoolean()).isTrue();
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

    private record OfflineNavigationProof(boolean workerRequestFailed,
                                          boolean pageResponseFromWorker,
                                          boolean offlineShellLoaded) { }

    private static OfflineNavigationProof proveWorkerOfflineNavigation(CdpBrowser browser, String base)
            throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "");
        String businessUrl = base + "/my/timesheet?nf04_offline_probe=" + token;
        String workerUrl = base + "/service-worker.js";
        JsonNode registration = JSON.readTree(browser.evaluateStrict("(async()=>{const r=await navigator.serviceWorker.getRegistration();"
                + "return JSON.stringify({scope:r&&r.scope,script:r&&r.active&&r.active.scriptURL,"
                + "controller:navigator.serviceWorker.controller&&navigator.serviceWorker.controller.scriptURL,"
                + "offline:!!(await caches.match('/offline.html'))})})()").asText());
        assertThat(registration.path("scope").asText()).isEqualTo(base + "/");
        assertThat(registration.path("script").asText()).isEqualTo(workerUrl);
        assertThat(registration.path("controller").asText()).isEqualTo(workerUrl);
        assertThat(registration.path("offline").asBoolean()).isTrue();

        JsonNode targets = browser.sendBrowser("Target.getTargets", Map.of()).path("targetInfos");
        String pageTargetId = null;
        String workerTargetId = null;
        for (JsonNode target : targets) {
            if ("page".equals(target.path("type").asText()) && target.path("url").asText().startsWith(base + "/my/timesheet")) {
                pageTargetId = target.path("targetId").asText();
            }
            if ("service_worker".equals(target.path("type").asText())
                    && workerUrl.equals(target.path("url").asText())) {
                if (workerTargetId != null) throw new IllegalStateException("同じscriptURLのworker targetが複数あります");
                workerTargetId = target.path("targetId").asText();
            }
        }
        assertThat(pageTargetId).as("対象のChrome page target").isNotBlank();
        assertThat(workerTargetId).as("登録scriptURLに一致するworker target").isNotBlank();
        String mainFrameId = browser.send("Page.getFrameTree", Map.of()).path("frameTree").path("frame").path("id").asText();
        int eventBoundary = browser.protocolEvents().size();
        Set<String> liveSessions = new HashSet<>();
        Set<String> pausedSessions = new HashSet<>();
        Set<String> configuredSessions = new HashSet<>();
        Throwable failure = null;
        OfflineNavigationProof proof = null;
        try {
            JsonNode attached = browser.sendBrowser("Target.attachToTarget", Map.of("targetId", workerTargetId, "flatten", true));
            String initialSession = attached.path("sessionId").asText();
            if (initialSession.isBlank()) throw new IllegalStateException("既存workerへのflatten attachに失敗しました");
            liveSessions.add(initialSession);
            configureWorkerOffline(browser, initialSession);
            configuredSessions.add(initialSession);
            browser.sendBrowser("Target.setDiscoverTargets", Map.of("discover", true));
            browser.sendBrowser("Target.setAutoAttach", Map.of("autoAttach", true,
                    "waitForDebuggerOnStart", true, "flatten", true,
                    "filter", List.of(Map.of("type", "service_worker"), Map.of("exclude", true))));
            browser.send("Network.emulateNetworkConditions", Map.of("offline", true, "latency", 0,
                    "downloadThroughput", 0, "uploadThroughput", 0, "connectionType", "none"));
            assertThat(browser.waitForStrict("!navigator.onLine", Duration.ofSeconds(10))).isTrue();
            JsonNode closed = browser.sendBrowser("Target.closeTarget", Map.of("targetId", workerTargetId));
            if (!closed.path("success").asBoolean()) throw new IllegalStateException("worker targetを停止できませんでした");
            browser.navigate(businessUrl);

            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            String restartedTargetId = null;
            boolean workerFailureObserved = false;
            boolean pageResponseObserved = false;
            boolean mainFrameNavigated = false;
            boolean shellObserved = false;
            while (System.nanoTime() < deadline && !(workerFailureObserved && pageResponseObserved && mainFrameNavigated)) {
                List<JsonNode> events = browser.protocolEvents();
                for (int i = eventBoundary; i < events.size(); i++) {
                    JsonNode event = events.get(i);
                    if (!"Target.attachedToTarget".equals(event.path("method").asText())) continue;
                    JsonNode info = event.path("params").path("targetInfo");
                    if (!"service_worker".equals(info.path("type").asText())
                            || !workerUrl.equals(info.path("url").asText())) continue;
                    String session = event.path("params").path("sessionId").asText();
                    restartedTargetId = info.path("targetId").asText();
                    if (!session.isBlank() && liveSessions.add(session)) {
                        pausedSessions.add(session);
                        configureWorkerOffline(browser, session);
                        configuredSessions.add(session);
                        browser.sendOnSession(session, "Runtime.runIfWaitingForDebugger", Map.of());
                        pausedSessions.remove(session);
                    }
                }
                for (int i = eventBoundary; i < events.size(); i++) {
                    JsonNode event = events.get(i);
                    String method = event.path("method").asText();
                    String eventTarget = event.path("codexTargetId").asText();
                    JsonNode params = event.path("params");
                    if ("Network.requestWillBeSent".equals(method)
                            && eventTarget.equals(restartedTargetId)
                            && businessUrl.equals(params.path("request").path("url").asText())) {
                        String requestId = params.path("requestId").asText();
                        String sessionId = event.path("codexSessionId").asText();
                        for (int j = eventBoundary; j < events.size(); j++) {
                            JsonNode failed = events.get(j);
                            if ("Network.loadingFailed".equals(failed.path("method").asText())
                                    && eventTarget.equals(failed.path("codexTargetId").asText())
                                    && sessionId.equals(failed.path("codexSessionId").asText())
                                    && requestId.equals(failed.path("params").path("requestId").asText())
                                    && "net::ERR_INTERNET_DISCONNECTED".equals(failed.path("params").path("errorText").asText())) {
                                workerFailureObserved = true;
                            }
                        }
                    }
                    if ("Network.requestWillBeSent".equals(method) && pageTargetId.equals(eventTarget)
                            && "Document".equals(params.path("type").asText())
                            && mainFrameId.equals(params.path("frameId").asText())
                            && businessUrl.equals(params.path("request").path("url").asText())) {
                        String requestId = params.path("requestId").asText();
                        for (int j = eventBoundary; j < events.size(); j++) {
                            JsonNode response = events.get(j);
                            if ("Network.responseReceived".equals(response.path("method").asText())
                                    && pageTargetId.equals(response.path("codexTargetId").asText())
                                    && requestId.equals(response.path("params").path("requestId").asText())
                                    && response.path("params").path("response").path("fromServiceWorker").asBoolean()) {
                                pageResponseObserved = true;
                            }
                        }
                    }
                    if ("Page.frameNavigated".equals(method) && pageTargetId.equals(eventTarget)
                            && mainFrameId.equals(params.path("frame").path("id").asText())
                            && businessUrl.equals(params.path("frame").path("url").asText())) {
                        mainFrameNavigated = true;
                    }
                }
                Thread.sleep(100);
            }
            if (mainFrameNavigated) {
                boolean rendered = browser.waitForStrict("location.href===" + JSON.writeValueAsString(businessUrl)
                        + "&&document.title.includes('オフライン')&&!navigator.onLine"
                        + "&&!!document.getElementById('offline-drafts')&&document.getElementById('offline-drafts').hidden"
                        + "&&!!document.getElementById('offline-confirm-restore')&&document.getElementById('offline-confirm-restore').hidden",
                        Duration.ofSeconds(10));
                shellObserved = rendered;
            }
            proof = new OfflineNavigationProof(workerFailureObserved, pageResponseObserved, shellObserved);
            if (!workerFailureObserved || !pageResponseObserved || !shellObserved) {
                throw new IllegalStateException("同一business navigationのworker実通信offline証跡が不足: " + proof);
            }
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            Throwable cleanupFailure = null;
            try { browser.sendBrowser("Target.setAutoAttach", Map.of("autoAttach", false,
                    "waitForDebuggerOnStart", false, "flatten", true)); }
            catch (Throwable thrown) { cleanupFailure = thrown; }
            List<JsonNode> cleanupEvents = browser.protocolEvents();
            for (int i = eventBoundary; i < cleanupEvents.size(); i++) {
                JsonNode event = cleanupEvents.get(i);
                if (!"Target.attachedToTarget".equals(event.path("method").asText())) continue;
                String session = event.path("params").path("sessionId").asText();
                if (session.isBlank()) continue;
                liveSessions.add(session);
                if (!configuredSessions.contains(session)) pausedSessions.add(session);
            }
            try {
                browser.send("Network.emulateNetworkConditions", Map.of("offline", false, "latency", 0,
                        "downloadThroughput", -1, "uploadThroughput", -1, "connectionType", "wifi"));
            } catch (Throwable thrown) {
                if (cleanupFailure == null) cleanupFailure = thrown; else cleanupFailure.addSuppressed(thrown);
            }
            for (String session : new HashSet<>(liveSessions)) {
                try {
                    if (!browser.isSessionAttached(session)) continue;
                    browser.sendOnSession(session, "Network.enable", Map.of());
                    browser.sendOnSession(session, "Network.emulateNetworkConditions", Map.of("offline", false,
                            "latency", 0, "downloadThroughput", -1, "uploadThroughput", -1, "connectionType", "wifi"));
                    if (pausedSessions.contains(session)) browser.sendOnSession(session, "Runtime.runIfWaitingForDebugger", Map.of());
                    browser.sendBrowser("Target.detachFromTarget", Map.of("sessionId", session));
                } catch (Throwable thrown) {
                    if (cleanupFailure == null) cleanupFailure = thrown; else cleanupFailure.addSuppressed(thrown);
                }
            }
            try {
                JsonNode bootstrap = JSON.readTree(browser.evaluateStrict("(async()=>{const r=await fetch('/api/my/session-context',"
                        + "{method:'GET',cache:'no-store',credentials:'same-origin',headers:{'Accept':'application/json'}});"
                        + "const j=await r.json();return JSON.stringify({status:r.status,code:j.code,"
                        + "cookie:document.cookie.split(';').some(x=>x.trim().startsWith('XSRF-TOKEN='))})})()").asText());
                if (bootstrap.path("status").asInt() != 200 || bootstrap.path("code").asInt() != 200
                        || !bootstrap.path("cookie").asBoolean()) throw new IllegalStateException("正規online CSRF bootstrapに失敗しました");
            } catch (Throwable thrown) { if (cleanupFailure == null) cleanupFailure = thrown; else cleanupFailure.addSuppressed(thrown); }
            if (cleanupFailure != null) {
                if (failure == null) failure = cleanupFailure; else failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) {
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException(failure);
        }
        return proof;
    }

    private static void configureWorkerOffline(CdpBrowser browser, String session) throws Exception {
        browser.sendOnSession(session, "Network.enable", Map.of());
        browser.sendOnSession(session, "Network.emulateNetworkConditions", Map.of("offline", true,
                "latency", 0, "downloadThroughput", 0, "uploadThroughput", 0, "connectionType", "none"));
    }
}
