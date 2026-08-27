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
    }

    @Test
    void shellにofflineページとmanifestリンクが存在する() throws Exception {
        assertThat(Files.exists(STATIC_ROOT.resolve("offline.html"))).isTrue();
        String layout = Files.readString(Path.of("src/main/resources/templates/layout/base.html"), StandardCharsets.UTF_8);
        assertThat(layout).contains("rel=\"manifest\"");
    }

    private String read(String name) throws Exception {
        return Files.readString(STATIC_ROOT.resolve(name), StandardCharsets.UTF_8);
    }
}
