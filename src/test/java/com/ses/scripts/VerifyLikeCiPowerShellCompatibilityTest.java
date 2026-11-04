package com.ses.scripts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R3-021: Windows PowerShell 5.1 / PowerShell 7のhelper互換を固定する。 */
class VerifyLikeCiPowerShellCompatibilityTest {

    private static final Path REPO = Path.of("").toAbsolutePath();
    private static final Path SCRIPT = REPO.resolve("scripts/verify-like-ci.ps1");
    private static final List<String> POWERSHELLS = List.of("powershell.exe", "pwsh");
    private static final String JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=C:\\temp";

    @Test
    void ps1はUtf8NoBomでeditorconfigにも方針がある() throws Exception {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        assertTrue(bytes.length >= 3);
        assertFalse(bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF);
        String script = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        assertTrue(script.contains("前提ツールの確認"));
        String editorConfig = Files.readString(REPO.resolve(".editorconfig"), StandardCharsets.UTF_8);
        assertTrue(editorConfig.contains("[*.ps1]"));
        assertTrue(editorConfig.contains("charset = utf-8"));
        assertFalse(editorConfig.contains("charset = utf-8-bom"));
    }

    @Test
    void 利用可能なPowerShellでpreflightとJVM診断を確認できる() throws Exception {
        List<String> executables = availablePowerShellExecutables();
        if (executables.isEmpty()) {
            assertStaticCompatibilityContract();
            return;
        }

        try (FixtureRepository fixture = createFixtureRepository()) {
            Path repository = fixture.repository();
            Path mockMaven = createMockMaven(repository);
            commitFixtureTools(repository);
            for (String executable : executables) {
                ProcessResult result = run(powerShellCommand(executable, repository, mockMaven, true));
                assertEquals(0, result.exitCode(), executable + "\n" + result.output());
                assertTrue(result.output().contains("PreflightOnly: 前提ツール確認まで正常に完了しました."), result.output());
                assertTrue(result.output().contains("Apache Maven 3.9.6"), result.output());
                assertTrue(result.output().contains("Picked up JAVA_TOOL_OPTIONS: " + JAVA_TOOL_OPTIONS), result.output());
            }
        }
    }

    @Test
    void Mavenビルド失敗はexitCodeと先行診断を保持する() throws Exception {
        List<String> executables = availablePowerShellExecutables();
        if (executables.isEmpty()) {
            assertStaticCompatibilityContract();
            return;
        }

        try (FixtureRepository fixture = createFixtureRepository()) {
            Path repository = fixture.repository();
            Path mockMaven = createMockMaven(repository);
            createToolMocks(repository);
            commitFixtureTools(repository);
            for (String executable : executables) {
                Files.deleteIfExists(repository.resolve("maven-build-invoked.txt"));
                Path evidence = fixture.root().resolve("private-evidence-" + executable.replaceAll("[^A-Za-z0-9]", ""));
                ProcessResult result = run(powerShellCommand(executable, repository, mockMaven, false, evidence));
                assertEquals(7, result.exitCode(), result.output());
                assertTrue(Files.exists(repository.resolve("maven-build-invoked.txt")),
                        "mock Mavenの-version呼出しでなくbuild/testまで到達すること\n" + result.output());
                int buildFailure = result.output().indexOf("Maven build/test failed");
                int skipDiagnosis = result.output().indexOf("skipされたテストの確認");
                assertTrue(buildFailure >= 0, result.output());
                assertTrue(skipDiagnosis < 0 || buildFailure < skipDiagnosis, result.output());
                assertTrue(result.output().contains("Picked up JAVA_TOOL_OPTIONS: " + JAVA_TOOL_OPTIONS), result.output());
                assertTrue(result.output().contains("Mock Maven build stderr diagnostic"), result.output());
                String suiteState = readUtf8Strict(evidence.resolve("fast-tests/state.json"));
                assertEquals(7, new ObjectMapper().readTree(suiteState).path("exitCode").asInt(-1), suiteState);
                String suiteLog = readTextWithEncodingBom(evidence.resolve("fast-tests/fast-tests.log"));
                assertTrue(suiteLog.contains("Mock Maven build stderr diagnostic"), suiteLog);
            }
        }
    }

    @Test
    void Mavenバージョン取得失敗ではsuiteを開始しない() throws Exception {
        List<String> executables = availablePowerShellExecutables();
        if (executables.isEmpty()) {
            assertStaticCompatibilityContract();
            return;
        }

        try (FixtureRepository fixture = createFixtureRepository()) {
            Path repository = fixture.repository();
            Path failingMaven = createMockMaven(repository, 9);
            commitFixtureTools(repository);
            for (String executable : executables) {
                Files.deleteIfExists(repository.resolve("maven-build-invoked.txt"));
                Path evidence = fixture.root().resolve("version-failure-evidence-" + executable.replaceAll("[^A-Za-z0-9]", ""));
                ProcessResult result = run(powerShellCommand(executable, repository, failingMaven, false, evidence));
                assertTrue(result.exitCode() != 0, result.output());
                assertTrue(result.output().contains("Mavenのバージョン確認に失敗しました (exit=9)"), result.output());
                assertFalse(Files.exists(repository.resolve("maven-build-invoked.txt")), result.output());
                assertFalse(Files.exists(evidence));
            }
        }
    }

    private List<String> powerShellCommand(String executable, Path fixture, Path mockMaven, boolean preflightOnly) {
        return powerShellCommand(executable, fixture, mockMaven, preflightOnly, null);
    }

    private List<String> powerShellCommand(String executable, Path fixture, Path mockMaven, boolean preflightOnly, Path evidence) {
        List<String> command = new ArrayList<>(List.of(executable, "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", fixture.resolve("scripts/verify-like-ci.ps1").toString(),
                "-MavenExecutable", mockMaven.toString()));
        if (preflightOnly) {
            command.add("-PreflightOnly");
        } else {
            command.addAll(List.of("-BashExecutable", fixture.resolve("tools/bash.cmd").toString(),
                    "-EvidenceDir", evidence.toString()));
        }
        return command;
    }

    private FixtureRepository createFixtureRepository() throws Exception {
        Path parent = Files.createTempDirectory("verify like-ci fixture ");
        Path fixture = parent.resolve("repository");
        try {
            Files.createDirectories(fixture.resolve("scripts/test-suites"));
            Files.createDirectories(fixture.resolve("tools"));
            Files.copy(REPO.resolve("scripts/verify-like-ci.ps1"), fixture.resolve("scripts/verify-like-ci.ps1"));
            Files.copy(REPO.resolve("scripts/verify-ci-common.ps1"), fixture.resolve("scripts/verify-ci-common.ps1"));
            for (int shard = 1; shard <= 3; shard++) {
                Files.copy(REPO.resolve("scripts/test-suites/mysql-shard-" + shard + ".txt"),
                        fixture.resolve("scripts/test-suites/mysql-shard-" + shard + ".txt"));
            }
            createToolMocks(fixture);
            git(fixture, "init");
            git(fixture, "config", "user.name", "SES Compatibility Test");
            git(fixture, "config", "user.email", "compatibility-test@example.invalid");
            git(fixture, "add", "scripts");
            git(fixture, "commit", "-m", "互換テスト用リポジトリを初期化");
            return new FixtureRepository(parent, fixture);
        } catch (Exception | Error failure) {
            try {
                deleteTree(parent);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void commitFixtureTools(Path fixture) throws Exception {
        git(fixture, "add", "tools");
        git(fixture, "commit", "-m", "互換テスト用ツールを追加");
    }

    private Path createMockMaven(Path fixture) throws IOException {
        return createMockMaven(fixture, 0);
    }

    private Path createMockMaven(Path fixture, int versionExitCode) throws IOException {
        Path mock = fixture.resolve("tools/mock-maven.cmd");
        Files.writeString(mock, "@echo off\r\n" +
                "if /I \"%~1\"==\"-v\" (\r\n" +
                "  echo Apache Maven 3.9.6\r\n" +
                "  >&2 echo Picked up JAVA_TOOL_OPTIONS: %JAVA_TOOL_OPTIONS%\r\n" +
                "  exit /b " + versionExitCode + "\r\n" +
                ")\r\n" +
                "echo BUILD_TEST_STARTED>\"%~dp0..\\maven-build-invoked.txt\"\r\n" +
                ">&2 echo Mock Maven build stderr diagnostic\r\n" +
                "exit /b 7\r\n", StandardCharsets.US_ASCII);
        return mock;
    }

    private void createToolMocks(Path fixture) throws IOException {
        Files.writeString(fixture.resolve("tools/docker.cmd"), "@echo off\r\nexit /b 0\r\n", StandardCharsets.US_ASCII);
        Files.writeString(fixture.resolve("tools/node.cmd"), "@echo off\r\necho v24.0.0\r\nexit /b 0\r\n", StandardCharsets.US_ASCII);
        Files.writeString(fixture.resolve("tools/bash.cmd"), "@echo off\r\nexit /b 0\r\n", StandardCharsets.US_ASCII);
        Files.writeString(fixture.resolve("tools/chrome.exe"), "mock", StandardCharsets.US_ASCII);
    }

    private void git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(args));
        ProcessResult result = run(command, Duration.ofSeconds(20), false);
        assertEquals(0, result.exitCode(), result.output());
    }

    private List<String> availablePowerShellExecutables() {
        List<String> available = new ArrayList<>();
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return available;
        }
        for (String candidate : POWERSHELLS) {
            try {
                ProcessResult result = run(List.of(candidate, "-NoProfile", "-Command", "$PSVersionTable.PSVersion.Major"),
                        Duration.ofSeconds(15), false);
                if (result.exitCode() == 0) {
                    available.add(candidate);
                }
            } catch (Exception ignored) {
                // PowerShell非搭載環境では静的契約を検証する。
            }
        }
        assertFalse(available.isEmpty(), "Windows環境ではPowerShell 5.1またはPowerShell 7が必要です。");
        return available;
    }

    private ProcessResult run(List<String> command) throws Exception {
        return run(command, Duration.ofSeconds(120), true);
    }

    private ProcessResult run(List<String> command, Duration timeout, boolean controlledEnvironment) throws Exception {
        Path outputFile = Files.createTempFile("verify-like-ci-process-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(REPO.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile());
            if (controlledEnvironment) {
                var environment = builder.environment();
                environment.put("JAVA_TOOL_OPTIONS", JAVA_TOOL_OPTIONS);
                for (String variable : List.of("MAVEN_ARGS", "MAVEN_OPTS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_CONFIG")) {
                    environment.remove(variable);
                }
                Path fixtureTools = Path.of(command.get(command.indexOf("-File") + 1)).getParent().getParent().resolve("tools");
                environment.put("PATH", fixtureTools + ";" + environment.getOrDefault("PATH", ""));
                environment.put("CHROME_BIN", fixtureTools.resolve("chrome.exe").toString());
            }
            process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.toHandle().descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                String output = readUtf8Strict(outputFile);
                throw new AssertionError("Process timed out: " + String.join(" ", command) + "\n" + output);
            }
            return new ProcessResult(process.exitValue(), readUtf8Strict(outputFile));
        } finally {
            if (process != null && process.isAlive()) {
                process.toHandle().descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
            }
            Files.deleteIfExists(outputFile);
        }
    }

    private void assertStaticCompatibilityContract() throws Exception {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        assertTrue(script.contains("PreflightOnly"));
        assertTrue(script.contains("MavenExecutable"));
        assertTrue(script.contains("Maven build/test failed"));
        assertTrue(script.contains("RedirectStandardError"));
        assertTrue(script.contains("Console]::OutputEncoding"));
        assertTrue(script.contains("UTF8_BODY_START"));
    }

    private String readUtf8Strict(Path path) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
    }

    private String readTextWithEncodingBom(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return StandardCharsets.UTF_16LE.decode(ByteBuffer.wrap(bytes, 2, bytes.length - 2)).toString();
        }
        return readUtf8Strict(path);
    }

    private static void deleteTree(Path root) throws IOException {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path rootName = absoluteRoot.getFileName();
        if (rootName == null || !rootName.toString().startsWith("verify like-ci fixture ")) {
            throw new IOException("fixture所有の一時ディレクトリ外の削除を拒否しました: " + absoluteRoot);
        }
        Path expectedParent = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!expectedParent.equals(absoluteRoot.getParent())) {
            throw new IOException("fixture一時ディレクトリの親境界が一致しません: " + absoluteRoot);
        }
        if (Files.isSymbolicLink(absoluteRoot)) {
            throw new IOException("fixture削除でシンボリックリンクrootを拒否しました: " + absoluteRoot);
        }
        if (!Files.exists(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // FOLLOW_LINKSを指定せず、所有fixture配下のエントリだけを列挙する。
        try (Stream<Path> paths = Files.walk(absoluteRoot)) {
            for (Path path : paths.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                Path absolutePath = path.toAbsolutePath().normalize();
                if (!absolutePath.startsWith(absoluteRoot)) {
                    throw new IOException("fixture削除対象が所有root外です: " + absolutePath);
                }
                if (!Files.isSymbolicLink(absolutePath)) {
                    DosFileAttributeView dos = Files.getFileAttributeView(
                            absolutePath, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    if (dos != null && dos.readAttributes().isReadOnly()) {
                        dos.setReadOnly(false);
                    }
                }
                Files.deleteIfExists(absolutePath);
            }
        }
    }

    private record FixtureRepository(Path root, Path repository) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            deleteTree(root);
        }
    }

    private record ProcessResult(int exitCode, String output) { }
}
