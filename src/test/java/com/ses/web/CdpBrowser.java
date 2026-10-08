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
    private WebSocket browserWs;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, String> targetIdsBySession = new ConcurrentHashMap<>();
    private final List<JsonNode> consoleEvents = new CopyOnWriteArrayList<>();
    private final List<JsonNode> networkRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> networkResponses = new CopyOnWriteArrayList<>();
    private final List<JsonNode> networkFailures = new CopyOnWriteArrayList<>();
    private final List<JsonNode> protocolEvents = new CopyOnWriteArrayList<>();

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
                try {
                    String content = Files.readString(portFile).trim();
                    if (!content.isBlank()) {
                        lines = content.split("\\s+");
                        if (lines.length >= 1) {
                            break;
                        }
                    }
                } catch (IOException locked) {
                    // Windows では起動直後に DevToolsActivePort が他プロセスでロックされ得る。
                    // 読み取り失敗は失敗扱いせず期限まで再試行する。
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
        String pageTargetId = null;
        for (JsonNode target : MAPPER.readTree(listJson)) {
            if ("page".equals(target.path("type").asText())) {
                wsUrl = target.path("webSocketDebuggerUrl").asText();
                pageTargetId = target.path("id").asText();
                break;
            }
        }
        if (wsUrl == null) {
            process.destroyForcibly();
            throw new IllegalStateException("page target が取得できません: " + listJson);
        }

        String browserWsUrl = MAPPER.readTree(client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/version"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).body())
                .path("webSocketDebuggerUrl").asText();
        if (browserWsUrl.isBlank()) {
            process.destroyForcibly();
            throw new IllegalStateException("browser websocket URL が取得できません");
        }
        CdpBrowser browser = new CdpBrowser(process);
        WebSocket ws = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .buildAsync(URI.create(wsUrl), browser.new Listener(pageTargetId))
                .get(30, TimeUnit.SECONDS);
        browser.ws = ws;
        browser.browserWs = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .buildAsync(URI.create(browserWsUrl), browser.new Listener(null))
                .get(30, TimeUnit.SECONDS);
        browser.send("Page.enable", Map.of());
        browser.send("Runtime.enable", Map.of());
        browser.send("Log.enable", Map.of());
        browser.send("Network.enable", Map.of());
        return browser;
    }

    private final class Listener implements WebSocket.Listener {
        private final String directTargetId;
        private final StringBuilder text = new StringBuilder();

        private Listener(String directTargetId) {
            this.directTargetId = directTargetId;
        }

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
                    handleMessage(msg, directTargetId);
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

    private void handleMessage(JsonNode msg, String directTargetId) {
        if (msg.has("id")) {
            CompletableFuture<JsonNode> future = pending.remove(msg.get("id").asLong());
            if (future != null) {
                future.complete(msg);
            }
        } else {
            ObjectNode event = ((ObjectNode) msg).deepCopy();
            String method = msg.path("method").asText("");
            String sessionId = msg.path("sessionId").asText("");
            if ("Target.attachedToTarget".equals(method)) {
                sessionId = msg.path("params").path("sessionId").asText("");
                String targetId = msg.path("params").path("targetInfo").path("targetId").asText("");
                if (!sessionId.isBlank() && !targetId.isBlank()) targetIdsBySession.put(sessionId, targetId);
            } else if ("Target.detachedFromTarget".equals(method)) {
                sessionId = msg.path("params").path("sessionId").asText("");
            }
            String targetId = sessionId.isBlank() ? directTargetId : targetIdsBySession.get(sessionId);
            if (targetId != null && !targetId.isBlank()) event.put("codexTargetId", targetId);
            if (!sessionId.isBlank()) event.put("codexSessionId", sessionId);
            protocolEvents.add(event);
            if ("Target.detachedFromTarget".equals(method) && !sessionId.isBlank()) {
                targetIdsBySession.remove(sessionId);
            }
            // 既存の単一page用アクセサーは従来どおりpage targetだけを返す。
            // worker/browser sessionの証跡はprotocolEvents()から個別に参照する。
            boolean pageEvent = directTargetId != null && sessionId.isBlank();
            if (pageEvent && ("Runtime.consoleAPICalled".equals(method) || "Log.entryAdded".equals(method))) {
                consoleEvents.add(event);
            } else if (pageEvent && "Network.requestWillBeSent".equals(method)) {
                networkRequests.add(event);
            } else if (pageEvent && "Network.responseReceived".equals(method)) {
                networkResponses.add(event);
            } else if (pageEvent && "Network.loadingFailed".equals(method)) {
                networkFailures.add(event);
            }
        }
    }

    /** CDPコマンドを送信し、応答を待つ。 */
    public JsonNode send(String method, Map<String, Object> params) throws Exception {
        return send(ws, null, method, params);
    }

    /** CDP応答を待たずにcommandを開始する。遷移とworker debugger解除の循環待ちを避ける。 */
    public CompletableFuture<JsonNode> sendAsync(String method, Map<String, Object> params) {
        return sendAsync(ws, null, method, params);
    }

    /** Page.navigateを非同期で開始し、loaderId/errorTextを含む応答Futureを返す。 */
    public CompletableFuture<JsonNode> startNavigation(String url) {
        return sendAsync("Page.navigate", Map.of("url", url));
    }

    /** browser targetに送信する。Target discovery/attach用。 */
    public JsonNode sendBrowser(String method, Map<String, Object> params) throws Exception {
        return send(browserWs, null, method, params);
    }

    /** flattened target sessionへCDP commandを送信する。 */
    public JsonNode sendOnSession(String sessionId, String method, Map<String, Object> params) throws Exception {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        return send(browserWs, sessionId, method, params);
    }

    private JsonNode send(WebSocket socket, String sessionId, String method,
                          Map<String, Object> params) throws Exception {
        try {
            return sendAsync(socket, sessionId, method, params).get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null) {
                cause = completion.getCause();
            }
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    private CompletableFuture<JsonNode> sendAsync(WebSocket socket, String sessionId, String method,
                                                   Map<String, Object> params) {
        if (socket == null) throw new IllegalStateException("CDP websocket is not connected");
        long id = nextId.getAndIncrement();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("method", method);
        if (sessionId != null) node.put("sessionId", sessionId);
        if (params != null) {
            node.set("params", MAPPER.valueToTree(params));
        }
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(id, response);
        socket.sendText(node.toString(), true).whenComplete((ignored, failure) -> {
            if (failure != null && pending.remove(id) != null) response.completeExceptionally(failure);
        });
        return response.thenApply(message -> {
            if (message.has("error")) throw new java.util.concurrent.CompletionException(
                    new IllegalStateException(method + " エラー: " + message.get("error")));
            return message.path("result");
        });
    }

    /** Chromeへ終了を指示し、このインスタンスが起動したprocessの終了を上限時間まで待つ。 */
    public boolean closeGracefully(Duration timeout) {
        if (process.isAlive()) {
            try {
                long id = nextId.getAndIncrement();
                ObjectNode node = MAPPER.createObjectNode();
                node.put("id", id);
                node.put("method", "Browser.close");
                ws.sendText(node.toString(), true).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 終了指示と競合してCDP接続が切れた場合もprocess終了を確認する。
            }
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return false;
            if (ws != null) ws.abort();
            return !process.isAlive();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
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

    /** JS例外やundefinedをnullへ潰さず、テスト失敗として扱う。 */
    public JsonNode evaluateStrict(String expression) throws Exception {
        JsonNode response;
        try {
            response = send("Runtime.evaluate", Map.of(
                    "expression", expression,
                    "returnByValue", true,
                    "awaitPromise", true));
        } catch (IllegalStateException protocolFailure) {
            String message = protocolFailure.getMessage();
            if (message != null && (message.contains("Cannot find context with specified id")
                    || message.contains("Execution context was destroyed"))) {
                throw new TransientEvaluationException();
            }
            throw protocolFailure;
        }
        if (response.has("exceptionDetails")) {
            JsonNode details = response.path("exceptionDetails");
            String text = details.path("text").asText("") + " "
                    + details.path("exception").path("description").asText("");
            if (text.contains("Execution context was destroyed")
                    || text.contains("Cannot find context with specified id")) throw new TransientEvaluationException();
            throw new IllegalStateException("ChromeのJavaScript評価が例外で終了しました");
        }
        JsonNode remote = response.path("result");
        if ("error".equals(remote.path("subtype").asText()) || !remote.has("value")) {
            throw new IllegalStateException("ChromeのJavaScript評価で値を取得できませんでした");
        }
        return remote.path("value");
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

    /** JS例外を隠さず、boolean式がtrueになるまで待つ。 */
    public boolean waitForStrict(String jsExpression, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            JsonNode value;
            try {
                value = evaluateStrict(jsExpression);
            } catch (TransientEvaluationException navigationRace) {
                Thread.sleep(100);
                continue;
            }
            if (!value.isBoolean()) throw new IllegalStateException("Chromeの待機条件がboolean値ではありません");
            if (value.asBoolean()) return true;
            Thread.sleep(300);
        }
        return false;
    }

    private static final class TransientEvaluationException extends IllegalStateException {
        private TransientEvaluationException() { super("ページ遷移で実行コンテキストが置き換わりました"); }
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

    public List<JsonNode> networkFailures() { return networkFailures; }

    public List<JsonNode> protocolEvents() { return protocolEvents; }

    public boolean isSessionAttached(String sessionId) { return targetIdsBySession.containsKey(sessionId); }

    @Override
    public void close() {
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } catch (Exception ignored) {
            // ignore
        }
        if (browserWs != null) {
            try { browserWs.sendClose(WebSocket.NORMAL_CLOSURE, "done"); }
            catch (Exception ignored) { /* process teardown closes the browser websocket */ }
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
