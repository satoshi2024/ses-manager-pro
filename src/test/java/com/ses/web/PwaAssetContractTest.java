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
        assertThat(worker).contains("path === '/js/i18n.js'");
        assertThat(worker).contains("request.method === 'GET'");
        assertThat(worker).contains("cache.put(request, response.clone())");
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
        assertThat(layout).contains("/js/pwa-queue.js", "id=\"pwa-queue-panel\"");
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
                "userScope", "createdAt", "MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000");
        assertThat(queue).contains("X-Client-Request-Id", "X-Client-Payload-Hash", "X-Client-Base-Version",
                "X-User-Scope", "cache: 'no-store'");
        assertThat(queue).contains("status === 'CONFLICT'", "サーバー版", "端末版", "端末版を破棄");
        assertThat(queue).doesNotContain("password", "bankAccount", "receiptBlob");
    }

    private String read(String name) throws Exception {
        return Files.readString(STATIC_ROOT.resolve(name), StandardCharsets.UTF_8);
    }
}
