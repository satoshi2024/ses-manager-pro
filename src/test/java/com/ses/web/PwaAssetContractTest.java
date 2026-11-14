package com.ses.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PwaAssetContractTest {

    private static final Path STATIC_ROOT = Path.of("src/main/resources/static");

    @Test
    void manifestは要員ポータルを起点にしpushを宣言しない() throws Exception {
        String manifest = read("manifest.webmanifest");

        assertThat(manifest).contains("\"start_url\": \"/my/timesheet\"");
        assertThat(manifest).contains("\"display\": \"standalone\"");
        assertThat(manifest).contains("\"src\": \"/favicon.svg\"");
        assertThat(manifest).doesNotContain("gcm_sender_id");
        assertThat(manifest).doesNotContain("push");
    }

    @Test
    void serviceWorkerはshellの明示allowlistだけをcacheする() throws Exception {
        String worker = read("service-worker.js");

        assertThat(worker).contains("const SHELL_ASSETS = [");
        // shell資産（pwa-queue.js・offline.html）を変えたためcache版を上げ、旧shellを入れ替える（W3-09）。
        assertThat(worker).contains("const CACHE_NAME = CACHE_PREFIX + 'v4'");
        assertThat(worker).doesNotContain("isDynamicOrSensitive");
        assertThat(worker).contains("path === '/js/i18n.js'");
        assertThat(worker).contains("request.method === 'GET'");
        assertThat(worker).contains("cache.put(request, response.clone())");
        assertThat(worker).contains("!response.redirected", "response.type !== 'opaque'", "redirect: 'error'");
        assertThat(worker).contains("event.respondWith(fetch(request).catch(() => caches.match('/offline.html'))");
        assertThat(worker).doesNotContain("caches.addAll([request])");
        assertThat(worker).doesNotContain("cache.put(request, await response");
        assertThat(worker).contains("'/js/pwa-queue.js'", "'/js/modules/my-timesheet.js'");
    }

    @Test
    void shellにofflineページとmanifestリンクが存在する() throws Exception {
        assertThat(Files.exists(STATIC_ROOT.resolve("offline.html"))).isTrue();
        String layout = Files.readString(Path.of("src/main/resources/templates/layout/base.html"), StandardCharsets.UTF_8);
        assertThat(layout).contains("rel=\"manifest\"");
        assertThat(layout).contains(
                "<div sec:authorize=\"hasRole('要員')\" id=\"pwa-queue-panel\"",
                "id=\"pwa-restore-link\" href=\"/offline.html\"",
                "<script sec:authorize=\"hasRole('要員')\" th:src=\"@{/js/pwa-queue.js}\"></script>");
    }

    @Test
    void offline下書きは日本語labelと表示許可項目だけを使う() throws Exception {
        String offline = read("offline.html");

        assertThat(offline).contains("下書きはロックされています", "再認証", "下書きを復元する",
                "勤怠", "作業報告", "未送信", "競合確認が必要",
                "workDate: '日付'", "clockIn: '出勤'", "expenseDate: '日付'");
        assertThat(offline).contains("ses:pwa-revoked", "prepareRestore", "confirmRestore")
                .doesNotContain("getOfflineDrafts();\n            if (!drafts.length) return");
        assertThat(offline.indexOf("confirmRestore.addEventListener('click'"))
                .isLessThan(offline.indexOf("getOfflineDrafts();"));
        assertThat(offline).doesNotContain("JSON.stringify(draft.payload", "<pre>", "draft.screen} /", "draft.status}`");
    }

    @Test
    void 共通shellはタッチ操作とキーボード操作の契約を持つ() throws Exception {
        String header = Files.readString(Path.of("src/main/resources/templates/layout/header.html"), StandardCharsets.UTF_8);
        String sidebar = Files.readString(Path.of("src/main/resources/templates/layout/sidebar.html"), StandardCharsets.UTF_8);
        String css = Files.readString(STATIC_ROOT.resolve("css/common.css"), StandardCharsets.UTF_8);

        assertThat(header).contains("id=\"pwa-status\"", "aria-live=\"polite\"", "aria-expanded=\"false\"");
        assertThat(sidebar).contains("id=\"sidebar-close-btn\"", "aria-label=\"メニューを閉じる\"");
        assertThat(css).contains("min-height: 44px", ":focus-visible", ".pwa-status[data-state=\"offline\"]");
    }

    @Test
    void offlineQueueは最小commandのscopeと保持期限を持つ() throws Exception {
        String queue = read("js/pwa-queue.js");

        assertThat(queue).contains("indexedDB", "clientRequestId", "payloadHash", "baseVersion",
                "userScope", "createdAt", "operationFor", "MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000");
        assertThat(queue).contains("X-Client-Request-Id", "X-Client-Payload-Hash", "X-Client-Base-Version",
                "X-Client-Created-At", "X-User-Scope", "cache: 'no-store'");
        assertThat(queue).contains("const contextHeaders", "contextHeaders['X-User-Scope'] = oldScope");
        assertThat(queue).contains("prepareRestore", "confirmRestore", "documentNonce: newDocumentNonce()",
                "newRequestIds", "restoredRequestIds", "rebindScope(oldScope, newScope", "deleteScope(oldScope)",
                "record.status === 'ERROR'", "refreshedFields", "serverRefreshAt");
        int bootstrapStart = queue.indexOf("async function bootstrapScope()");
        int bootstrapEnd = queue.indexOf("async function cleanupExpired()", bootstrapStart);
        assertThat(queue.substring(bootstrapStart, bootstrapEnd)).doesNotContain("rebindScope")
                .doesNotContain("readAll(")
                .contains("preserveQueue", "deleteScope(oldScope)");
        assertThat(queue).contains("function validMethod", "path === '/api/my/pwa/expenses/drafts'",
                "return method === 'PUT'", "URL IDとpayload.idが一致しません", "row.days", "row.dailies");
        assertThat(queue).contains("QUEUE_EXPIRED", "error.pwa.queueExpired");
        assertThat(queue).contains("sanitizeConflict", "REVOCATION_KEY", "SCOPE_EPOCH_KEY",
                "BroadcastChannel", "claimAggregate", "revocationEpoch", "getOfflineDrafts", "clearDraftDom");
        assertThat(queue).contains("const DB_VERSION = 2", "const META_STORE = 'meta'",
                "DB_VERSION", "record.conflict", "record.payload = null");
        assertThat(queue).contains("state.paused = true", "response.redirected", "text/html");
        assertThat(queue).contains("status === 'CONFLICT'", "サーバー版", "端末版", "端末版を破棄");
        assertThat(queue).contains("FORBIDDEN_KEY", "MAX_PAYLOAD_BYTES", "validatePayload", "ArrayBuffer.isView");
        assertThat(queue).doesNotContain("record.password", "record.bankAccount", "record.receiptBlob");
        assertThat(queue).contains("/api/my/pwa/attendance/daily", "/api/my/pwa/timesheet/daily",
                "expenses\\/drafts", "/api/my/pwa/change-requests/drafts",
                "data-pwa-refresh", "data-pwa-reapply", "serverRefreshAt");
    }

    @Test
    void serviceWorker判定より前にlogout時の確認とscope削除を結び付ける() throws Exception {
        String common = read("js/common.js");
        String sessionApi = Files.readString(Path.of(
                "src/main/java/com/ses/controller/api/PwaSessionApiController.java"), StandardCharsets.UTF_8);

        // R1 P3-N4-1: serviceWorker非対応環境（非secure context・一部WebView）でreturnする前にlogoutを結び付ける。
        int pwaStart = common.indexOf("SES.pwa = {");
        int initStart = common.indexOf("init: function()", pwaStart);
        int bind = common.indexOf("this.bindLogout();", initStart);
        int swGuard = common.indexOf("if (!('serviceWorker' in navigator)) return;", initStart);
        int initEnd = common.indexOf("shellDisabled: function()", initStart);
        assertThat(pwaStart).isNotNegative();
        assertThat(bind).isGreaterThan(initStart).isLessThan(swGuard);
        assertThat(swGuard).isLessThan(initEnd);
        assertThat(common.substring(swGuard, initEnd)).doesNotContain("this.bindLogout();");
        assertThat(common).contains("'serviceWorker' in navigator && navigator.serviceWorker.controller");
        // logoutは未送信・ロック中の下書きがあるときだけ確認し、取消時は削除もlogoutもしない。
        int bindLogout = common.indexOf("bindLogout: function()");
        String logout = common.substring(bindLogout, common.indexOf("};", bindLogout));
        assertThat(logout).contains("self.confirmDraftLoss().then(function(proceed)", "if (!proceed) return;",
                "self.clearUserScope()");
        assertThat(logout.indexOf("confirmDraftLoss")).isLessThan(logout.indexOf("clearUserScope"));
        assertThat(common).contains("draftLossSummary", "pwa.logout.draftLossConfirm");
        assertThat(sessionApi).contains("@RequestHeader(value = \"X-User-Scope\", required = false)",
                "resolve(presentedScope)", "preserveQueue", "\"restoreAvailable\"",
                "supportsPasswordReauthentication(authentication)");
    }

    @Test
    void 復元できないsessionでは復元入口を隠し離脱前に確認する() throws Exception {
        String queue = read("js/pwa-queue.js");
        String offline = read("offline.html");

        assertThat(queue).contains("state.restoreAvailable = result.data.restoreAvailable === true",
                "restoreLink.hidden = state.lockedDraftCount === 0 || state.restoreAvailable !== true",
                "pwa.queue.lockedUnavailable", "pwa.queue.unsentLeaveWarning", "pwa.restore.unavailable",
                "PWA_RESTORE_REAUTH_UNSUPPORTED", "checkRestoreAvailability", "draftLossSummary");
        // beforeunloadは必要な間だけ登録し、復元可能なsession（true）では出さない。
        assertThat(queue).contains("state.restoreAvailable !== true && state.unsentDraftCount > 0",
                "window.addEventListener('beforeunload', onBeforeUnload)",
                "window.removeEventListener('beforeunload', onBeforeUnload)",
                "'visibilitychange'", "'pagehide', flushBeforeLeaving");
        // パスワードを送る前に復元可否を確かめる。
        String prepare = queue.substring(queue.indexOf("prepareRestore: async password =>"),
                queue.indexOf("confirmRestore: async () =>"));
        assertThat(prepare.indexOf("checkRestoreAvailability()"))
                .isNotNegative()
                .isLessThan(prepare.indexOf("/api/my/pwa/restore/reauthenticate"));
        // 応答喪失・5xxのrecordより後の同aggregateは同じflushで送らない（PRD-NF04-002）。
        assertThat(queue).contains("if (result && (result.conflict || result.queued)) blocked.add(record.resourceKey);");
        // offline.htmlは復元可否を確認するまでパスワード欄を使わせず、不可ならパスワード欄を隠す。
        assertThat(offline).contains("id=\"offline-restore-auth\"", "id=\"offline-restore-unavailable\"",
                "この下書きは復元できません", "checkRestoreAvailability()", "restoreAvailability === true",
                "error.restoreUnavailable", "window.addEventListener('online', refreshAvailability)");
    }

    @Test
    void 撤回epochのcookieは照合済みの同じ値だけで期限を延ばしhttpsではSecureを付ける() throws Exception {
        String queue = read("js/pwa-queue.js");

        int refresh = queue.indexOf("function refreshRevocationEpochCookie(epoch)");
        assertThat(refresh).isNotNegative();
        String refreshBody = queue.substring(refresh, queue.indexOf("function scopeTicket()", refresh));
        assertThat(refreshBody).contains("readRevocationEpoch() !== epoch", "writeRevocationCookie(epoch)")
                .doesNotContain("localStorage.setItem");
        assertThat(queue).contains("location.protocol === 'https:' ? '; Secure' : ''",
                "Max-Age=31536000; Path=/; SameSite=Strict${secure}");
        int bootstrapStart = queue.indexOf("async function bootstrapScope()");
        String bootstrap = queue.substring(bootstrapStart, queue.indexOf("async function cleanupExpired()", bootstrapStart));
        assertThat(bootstrap).contains("refreshRevocationEpochCookie(bootstrapEpoch)");
    }

    @Test
    void server側retentionと監視はpayloadやuser識別子をtagへ出さない() throws Exception {
        String scheduler = Files.readString(Path.of(
                "src/main/java/com/ses/service/pwa/PwaClientMutationCleanupScheduler.java"), StandardCharsets.UTF_8);
        String metrics = Files.readString(Path.of(
                "src/main/java/com/ses/service/pwa/PwaMutationMetrics.java"), StandardCharsets.UTF_8);

        assertThat(scheduler).contains("queue-retention-days", "deleteOlderThan", "PWA mutation ledger cleanup completed");
        assertThat(metrics).contains("ses.pwa.mutations", "outcome", "screen");
        assertThat(metrics).doesNotContain("userId", "payload", "userScope");
    }

    private String read(String name) throws Exception {
        return Files.readString(STATIC_ROOT.resolve(name), StandardCharsets.UTF_8);
    }
}
