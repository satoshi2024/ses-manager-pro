package com.ses.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 最小限のChrome DevTools Protocol（CDP）クライアント（R7-P2-04対応）。
 *
 * <p>JDK標準の {@link java.net.http.WebSocket} とJacksonのみでChrome/Chromiumをheadless制御する。
 * Selenium等の外部ブラウザ自動化依存を追加しない（オフラインのL4でも再現可能にするため）。
 * ログイン・ページ遷移・DOM検証・スクリーンショット・コンソール/ネットワークイベント収集を
 * 同一ブラウザセッション（同一Cookieコンテキスト）で行う。
 */
public final class CdpBrowser implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

    private final Process process;
    private WebSocket ws;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<JsonNode> consoleEvents = new CopyOnWriteArrayList<>();
    private final List<JsonNode> networkRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> networkResponses = new CopyOnWriteArrayList<>();

    private CdpBrowser(Process process) {
        this.process = process;
    }

    /** Chrome/Chromiumの実行ファイルを探索する（CHROME_BIN環境変数が最優先）。 */
    public static Path chromeExecutable() {
        String env = System.getenv("CHROME_BIN");
        if (env != null && !env.isBlank() && Files.exists(Path.of(env))) {
            return Path.of(env);
        }
        List<Path> candidates = List.of(
                Path.of("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"),
                Path.of("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"),
                Path.of("/usr/bin/google-chrome"),
                Path.of("/usr/bin/google-chrome-stable"),
                Path.of("/usr/bin/chromium"),
                Path.of("/usr/bin/chromium-browser"),
                Path.of("/snap/bin/chromium"));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Chrome/Chromium が見つかりません。CHROME_BIN環境変数で実行ファイルを指定してください");
    }

    /** Chromeをheadlessで起動し、最初のpage targetへCDP接続する。 */
    public static CdpBrowser launch(Path chromeExe, Path userDataDir, int width, int height) throws Exception {
        Files.createDirectories(userDataDir);
        ProcessBuilder pb = new ProcessBuilder(
                chromeExe.toString(),
                "--headless=new",
                "--remote-debugging-port=0",
                "--user-data-dir=" + userDataDir,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-gpu",
                "--window-size=" + width + "," + height,
                "about:blank");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        Thread drain = new Thread(() -> {
            try {
                process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // ignore
            }
        });
        drain.setDaemon(true);
        drain.start();

        Path portFile = userDataDir.resolve("DevToolsActivePort");
        String[] lines = null;
        long deadline = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(portFile)) {
                String content = Files.readString(portFile).trim();
                if (!content.isBlank()) {
                    lines = content.split("\\s+");
                    if (lines.length >= 1) {
                        break;
                    }
                }
            }
            Thread.sleep(200);
        }
        if (lines == null) {
            process.destroyForcibly();
            throw new IllegalStateException("Chrome DevToolsActivePort が生成されませんでした");
        }
        int port = Integer.parseInt(lines[0]);

        HttpClient client = HttpClient.newHttpClient();
        String listJson = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        String wsUrl = null;
        for (JsonNode target : MAPPER.readTree(listJson)) {
            if ("page".equals(target.path("type").asText())) {
                wsUrl = target.path("webSocketDebuggerUrl").asText();
                break;
            }
        }
        if (wsUrl == null) {
            process.destroyForcibly();
            throw new IllegalStateException("page target が取得できません: " + listJson);
        }

        CdpBrowser browser = new CdpBrowser(process);
        WebSocket ws = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .buildAsync(URI.create(wsUrl), browser.new Listener())
                .get(30, TimeUnit.SECONDS);
        browser.ws = ws;
        browser.send("Page.enable", Map.of());
        browser.send("Runtime.enable", Map.of());
        browser.send("Log.enable", Map.of());
        browser.send("Network.enable", Map.of());
        return browser;
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try {
                    JsonNode msg = MAPPER.readTree(text.toString());
                    handleMessage(msg);
                } catch (Exception ignored) {
                    // 不正なメッセージは無視
                }
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            // 接続エラーは send() 側のタイムアウトで検知する
        }
    }

    private void handleMessage(JsonNode msg) {
        if (msg.has("id")) {
            CompletableFuture<JsonNode> future = pending.remove(msg.get("id").asLong());
            if (future != null) {
                future.complete(msg);
            }
        } else {
            String method = msg.path("method").asText("");
            if ("Runtime.consoleAPICalled".equals(method) || "Log.entryAdded".equals(method)) {
                consoleEvents.add(msg);
            } else if ("Network.requestWillBeSent".equals(method)) {
                networkRequests.add(msg);
            } else if ("Network.responseReceived".equals(method)) {
                networkResponses.add(msg);
            }
        }
    }

    /** CDPコマンドを送信し、応答を待つ。 */
    public JsonNode send(String method, Map<String, Object> params) throws Exception {
        long id = nextId.getAndIncrement();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("method", method);
        if (params != null) {
            node.set("params", MAPPER.valueToTree(params));
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ws.sendText(node.toString(), true);
        JsonNode message = future.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (message.has("error")) {
            throw new IllegalStateException(method + " エラー: " + message.get("error"));
        }
        return message.path("result");
    }

    /** ページ内JSを評価し、値（returnByValue）を返す。例外・実行コンテキスト破棄時はnullNode。 */
    public JsonNode evaluate(String expression) throws Exception {
        try {
            JsonNode result = send("Runtime.evaluate", Map.of(
                    "expression", expression,
                    "returnByValue", true,
                    "awaitPromise", true));
            JsonNode value = result.path("result").path("value");
            return value.isMissingNode() ? MAPPER.nullNode() : value;
        } catch (Exception e) {
            return MAPPER.nullNode();
        }
    }

    public void navigate(String url) throws Exception {
        send("Page.navigate", Map.of("url", url));
    }

    public void setDeviceMetrics(int width, int height, boolean mobile, double scale) throws Exception {
        send("Emulation.setDeviceMetricsOverride", Map.of(
                "width", width, "height", height, "deviceScaleFactor", scale, "mobile", mobile));
    }

    public String currentUrl() throws Exception {
        return evaluate("location.href").asText("");
    }

    /** 指定JS式がtrueになるまで待つ。 */
    public boolean waitFor(String jsExpression, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            JsonNode value = evaluate(jsExpression);
            if (value.isBoolean() && value.asBoolean()) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    public byte[] screenshot() throws Exception {
        JsonNode result = send("Page.captureScreenshot", Map.of("format", "png"));
        return Base64.getDecoder().decode(result.path("data").asText());
    }

    public List<JsonNode> consoleEvents() {
        return consoleEvents;
    }

    public List<JsonNode> networkRequests() {
        return networkRequests;
    }

    public List<JsonNode> networkResponses() {
        return networkResponses;
    }

    @Override
    public void close() {
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } catch (Exception ignored) {
            // ignore
        }
        process.destroy();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
