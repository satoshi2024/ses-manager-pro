package com.ses.scripts;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R3-002/R3-003/R3-004: capacity harnessの集計・credential契約を実行検証する。 */
class CapacityBaselineScriptTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 誤passwordはsetupErrorを集計して非0終了する() throws Exception {
        if (!canRunPowerShell()) {
            assertStaticSummaryContract();
            return;
        }
        FixtureServer fixture = startFixtureServer(Map.of("valid-user", "correct-password"));
        Path root = Files.createTempDirectory("capacity-wrong-password-");
        Path credentials = writeCredentials(root, List.of(new Credential("valid-user", "wrong-password")));

        ProcessResult result = runScript(root, fixture.baseUrl(), List.of(
                "-Stages", "1", "-Scenario", "login-spike", "-CredentialFile", credentials.toString()));

        assertNotEquals(0, result.exitCode());
        Map<String, String> summary = readOnlySummary(root);
        assertEquals("1", summary.get("RequestedUsers"));
        assertEquals("0", summary.get("AuthenticatedUsers"));
        assertEquals("1", summary.get("SetupErrors"));
        assertEquals("0", summary.get("RequestErrors"));
        assertEquals("1", summary.get("TotalErrors"));
        assertTrue(summary.get("ErrorClassification").contains("login-failed=1"));
        assertEquals(2, Files.readAllLines(findArtifact(root, "requests.csv"), StandardCharsets.UTF_8).size());
    }

    @Test
    void 単一credentialでsession上限を超えるstageはpreflightで拒否する() throws Exception {
        if (!canRunPowerShell()) {
            assertStaticCredentialContract();
            return;
        }
        Path root = Files.createTempDirectory("capacity-single-credential-");

        ProcessResult result = runScript(root, "http://127.0.0.1:1", List.of(
                "-Stages", "10", "-Username", "single-user", "-Password", "not-exported",
                "-ExpectedMaxConcurrentSessions", "5", "-CheckOnly"));

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("session"));
    }

    @Test
    void credentialFileが最大stage未満ならpreflightで拒否する() throws Exception {
        if (!canRunPowerShell()) {
            assertStaticCredentialContract();
            return;
        }
        Path root = Files.createTempDirectory("capacity-short-credentials-");
        Path credentials = writeCredentials(root, List.of(
                new Credential("user-1", "secret-1"), new Credential("user-2", "secret-2")));

        ProcessResult result = runScript(root, "http://127.0.0.1:1", List.of(
                "-Stages", "3", "-CredentialFile", credentials.toString(), "-CheckOnly"));

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("credential"));
    }

    @Test
    void 十個のcredentialをworkerへ一意割当しsecretを成果物へ出さない() throws Exception {
        if (!canRunPowerShell()) {
            assertStaticCredentialContract();
            return;
        }
        Map<String, String> accepted = new LinkedHashMap<>();
        List<Credential> credentialsList = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String username = "unique-user-" + i;
            String password = "fixture-secret-" + i;
            accepted.put(username, "*");
            credentialsList.add(new Credential(username, password));
        }
        FixtureServer fixture = startFixtureServer(accepted);
        Path root = Files.createTempDirectory("capacity-unique-credentials-");
        Path credentials = writeCredentials(root, credentialsList);

        ProcessResult result = runScript(root, fixture.baseUrl(), List.of(
                "-Stages", "10", "-Scenario", "login-spike", "-CredentialFile", credentials.toString()));

        assertEquals(0, result.exitCode(), result.output() + "\nattempts=" + fixture.loginAttempts()
                + " loggedIn=" + fixture.loggedInUsers());
        assertEquals(10, fixture.loggedInUsers().size());
        assertEquals(11, fixture.loginAttempts().size(), "worker 10件と監視session 1件のloginであること");
        Map<String, String> summary = readOnlySummary(root);
        assertEquals("10", summary.get("RequestedUsers"));
        assertEquals("10", summary.get("AuthenticatedUsers"));
        assertEquals("0", summary.get("TotalErrors"));
        assertEquals(11, Files.readAllLines(findArtifact(root, "requests.csv"), StandardCharsets.UTF_8).size());
        String artifacts = readArtifacts(root);
        for (Credential credential : credentialsList) {
            assertFalse(artifacts.contains(credential.password()), "passwordを成果物へ保存しないこと");
        }
        assertFalse(artifacts.contains("JSESSIONID"));
        assertFalse(artifacts.contains("XSRF-TOKEN"));
    }

    @Test
    void actuator401はUnavailableとなりRequireMetricsで非0終了する() throws Exception {
        if (!canRunPowerShell()) {
            String script = Files.readString(Path.of("scripts", "capacity-baseline.ps1"));
            assertTrue(script.contains("RequireMetrics"));
            assertTrue(script.contains("Available"));
            return;
        }
        FixtureServer fixture = startFixtureServer(Map.of("metrics-user", "metrics-password"), true);
        Path root = Files.createTempDirectory("capacity-metrics-unavailable-");
        Path credentials = writeCredentials(root,
                List.of(new Credential("metrics-user", "metrics-password")));

        ProcessResult result = runScript(root, fixture.baseUrl(), List.of(
                "-Stages", "1", "-Scenario", "login-spike", "-CredentialFile", credentials.toString(),
                "-RequireMetrics"));

        assertNotEquals(0, result.exitCode());
        String monitor = Files.readString(findArtifact(root, "monitor-snapshots.json"), StandardCharsets.UTF_8);
        assertTrue(monitor.contains("\"Available\": false") || monitor.contains("\"Available\":false"));
        assertTrue(monitor.contains("HTTP 401"));
    }

    private ProcessResult runScript(Path outputDirectory, String baseUrl, List<String> extraArgs) throws Exception {
        String executable = powerShellExecutable();
        List<String> command = new ArrayList<>(List.of(executable, "-NoProfile", "-File",
                Path.of("scripts", "capacity-baseline.ps1").toAbsolutePath().toString(),
                "-BaseUrl", baseUrl,
                "-StageDurationSeconds", "0",
                "-ThinkTimeMs", "0",
                "-OutputDirectory", outputDirectory.toString(),
                "-SkipExport", "-SkipUpdates"));
        command.addAll(extraArgs);
        Process process = new ProcessBuilder(command)
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("capacity scriptが45秒以内に終了しませんでした");
        }
        return new ProcessResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private FixtureServer startFixtureServer(Map<String, String> acceptedCredentials) throws IOException {
        return startFixtureServer(acceptedCredentials, false);
    }

    private FixtureServer startFixtureServer(Map<String, String> acceptedCredentials,
                                             boolean actuatorUnauthorized) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> attempts = Collections.synchronizedList(new ArrayList<>());
        java.util.Set<String> loggedIn = ConcurrentHashMap.newKeySet();
        server.createContext("/", exchange -> handleFixtureRequest(
                exchange, acceptedCredentials, attempts, loggedIn, actuatorUnauthorized));
        server.start();
        return new FixtureServer("http://127.0.0.1:" + server.getAddress().getPort(), attempts, loggedIn);
    }

    private void handleFixtureRequest(HttpExchange exchange, Map<String, String> acceptedCredentials,
                                      List<String> attempts, java.util.Set<String> loggedIn,
                                      boolean actuatorUnauthorized) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (actuatorUnauthorized && path.startsWith("/actuator/")) {
            send(exchange, 401, "unauthorized");
            return;
        }
        if ("/login".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=fixture-token; Path=/");
            send(exchange, 200, "login");
            return;
        }
        if ("/login".equals(path) && "POST".equals(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            String username = form.getOrDefault("username", "");
            attempts.add(username);
            String expectedPassword = acceptedCredentials.get(username);
            boolean passwordAccepted = "*".equals(expectedPassword)
                    || (expectedPassword != null && expectedPassword.equals(form.get("password")));
            if (passwordAccepted) {
                loggedIn.add(username);
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=session-" + username + "; Path=/");
                exchange.getResponseHeaders().add("Location", "/");
            } else {
                exchange.getResponseHeaders().add("Location", "/login?error");
            }
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        send(exchange, 200, "{}");
    }

    private Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            form.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return form;
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Path writeCredentials(Path directory, List<Credential> credentials) throws IOException {
        Path path = directory.resolve("credentials.csv");
        StringBuilder csv = new StringBuilder("username,password\n");
        for (Credential credential : credentials) {
            csv.append(credential.username()).append(',').append(credential.password()).append('\n');
        }
        return Files.writeString(path, csv, StandardCharsets.UTF_8);
    }

    private Map<String, String> readOnlySummary(Path root) throws IOException {
        Path summary = findArtifact(root, "summary.csv");
        List<String> lines = Files.readAllLines(summary, StandardCharsets.UTF_8);
        String[] headers = unquoteCsv(lines.get(0));
        String[] values = unquoteCsv(lines.get(1));
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            result.put(headers[i], i < values.length ? values[i] : "");
        }
        return result;
    }

    private Path findArtifact(Path root, String fileName) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst().orElseThrow();
        }
    }

    private String[] unquoteCsv(String line) {
        return java.util.Arrays.stream(line.split(",", -1))
                .map(v -> v.replaceFirst("^\"", "").replaceFirst("\"$", "").replace("\"\"", "\""))
                .toArray(String[]::new);
    }

    private String readArtifacts(Path root) throws IOException {
        StringBuilder result = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().equals("credentials.csv")) {
                    result.append(Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        }
        return result.toString();
    }

    private boolean canRunPowerShell() {
        return powerShellExecutable() != null;
    }

    private String powerShellExecutable() {
        for (String candidate : List.of("pwsh", "powershell.exe")) {
            try {
                Process process = new ProcessBuilder(candidate, "-NoProfile", "-Command", "$PSVersionTable.PSVersion.Major")
                        .redirectErrorStream(true).start();
                if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // PowerShellなしのCIではstatic contractを検証する。
            }
        }
        return null;
    }

    private void assertStaticSummaryContract() throws IOException {
        String script = Files.readString(Path.of("scripts", "capacity-baseline.ps1"));
        for (String field : List.of("RequestedUsers", "AuthenticatedUsers", "SetupErrors",
                "RequestErrors", "TotalErrors", "ErrorClassification")) {
            assertTrue(script.contains(field), field + "をsummaryへ含めること");
        }
    }

    private void assertStaticCredentialContract() throws IOException {
        String script = Files.readString(Path.of("scripts", "capacity-baseline.ps1"));
        assertTrue(script.contains("CredentialFile"));
        assertTrue(script.contains("ExpectedMaxConcurrentSessions"));
        assertTrue(script.contains("AllowSingleCredentialSessionEviction"));
    }

    private record Credential(String username, String password) { }
    private record ProcessResult(int exitCode, String output) { }
    private record FixtureServer(String baseUrl, List<String> loginAttempts, java.util.Set<String> loggedInUsers) { }
}
