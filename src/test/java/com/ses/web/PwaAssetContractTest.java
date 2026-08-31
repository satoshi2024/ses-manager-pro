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
        assertThat(worker).contains("const CACHE_NAME = CACHE_PREFIX + 'v2'");
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
                "<script sec:authorize=\"hasRole('要員')\" th:src=\"@{/js/pwa-queue.js}\"></script>");
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
        assertThat(queue).contains("preserveQueue", "rebindScope(oldScope, scope)", "deleteScope(oldScope)",
                "record.status === 'ERROR'", "refreshedFields", "serverRefreshAt");
        assertThat(queue).contains("function validMethod", "path === '/api/my/pwa/expenses/drafts'",
                "return method === 'PUT'", "URL IDとpayload.idが一致しません", "row.days", "row.dailies");
        assertThat(queue).contains("QUEUE_EXPIRED", "error.pwa.queueExpired");
        assertThat(queue).contains("state.paused = true", "response.redirected", "text/html");
        assertThat(queue).contains("status === 'CONFLICT'", "サーバー版", "端末版", "端末版を破棄");
        assertThat(queue).contains("FORBIDDEN_KEY", "MAX_PAYLOAD_BYTES", "validatePayload", "ArrayBuffer.isView");
        assertThat(queue).doesNotContain("record.password", "record.bankAccount", "record.receiptBlob");
        assertThat(queue).contains("/api/my/pwa/attendance/daily", "/api/my/pwa/timesheet/daily",
                "expenses\\/drafts", "/api/my/pwa/change-requests/drafts",
                "data-pwa-refresh", "data-pwa-reapply", "serverRefreshAt");
    }

    @Test
    void serviceWorker非対応環境でもlogout時のscope削除を継続する() throws Exception {
        String common = read("js/common.js");
        String sessionApi = Files.readString(Path.of(
                "src/main/java/com/ses/controller/api/PwaSessionApiController.java"), StandardCharsets.UTF_8);

        assertThat(common).contains("'serviceWorker' in navigator && navigator.serviceWorker.controller");
        assertThat(sessionApi).contains("@RequestHeader(value = \"X-User-Scope\", required = false)",
                "resolve(presentedScope)", "preserveQueue");
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
