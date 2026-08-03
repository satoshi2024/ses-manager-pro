package com.ses.scripts;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R3-021: Windows PowerShell 5.1 / PowerShell 7のhelper互換を固定する。 */
class VerifyLikeCiPowerShellCompatibilityTest {

    private static final Path SCRIPT = Path.of("scripts", "verify-like-ci.ps1").toAbsolutePath();

    @Test
    void ps1はUtf8Bomでeditorconfigにも方針がある() throws Exception {
        byte[] bytes = Files.readAllBytes(SCRIPT);
        assertTrue(bytes.length >= 3);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        String editorConfig = Files.readString(Path.of(".editorconfig"), StandardCharsets.UTF_8);
        assertTrue(editorConfig.contains("[*.ps1]"));
        assertTrue(editorConfig.contains("charset = utf-8-bom"));
    }

    @Test
    void 利用可能なPowerShellでpreflightまで実行できる() throws Exception {
        List<String> executables = availablePowerShellExecutables();
        if (executables.isEmpty()) {
            assertStaticCompatibilityContract();
            return;
        }
        for (String executable : executables) {
            ProcessResult result = run(List.of(executable, "-NoProfile", "-File", SCRIPT.toString(),
                    "-PreflightOnly"));
            assertEquals(0, result.exitCode(), executable + "\n" + result.output());
            assertTrue(result.output().contains("PreflightOnly"));
        }
    }

    @Test
    void Maven失敗はskip診断より先に明示して元のexitCodeを返す() throws Exception {
        List<String> executables = availablePowerShellExecutables();
        if (executables.isEmpty() || !System.getProperty("os.name").toLowerCase().contains("win")) {
            assertStaticCompatibilityContract();
            return;
        }
        Path directory = Files.createTempDirectory("verify-like-ci-failing-maven-");
        Path failing = directory.resolve("failing-maven.cmd");
        Files.writeString(failing, "@echo off\r\nexit /b 7\r\n", StandardCharsets.US_ASCII);

        ProcessResult result = run(List.of(executables.get(0), "-NoProfile", "-File", SCRIPT.toString(),
                "-MavenExecutable", failing.toString()));

        assertEquals(7, result.exitCode(), result.output());
        int buildFailure = result.output().indexOf("Maven build/test failed");
        int skipDiagnosis = result.output().indexOf("skipされたテストの確認");
        assertTrue(buildFailure >= 0, result.output());
        assertTrue(skipDiagnosis < 0 || buildFailure < skipDiagnosis, result.output());
    }

    private List<String> availablePowerShellExecutables() {
        List<String> available = new ArrayList<>();
        for (String candidate : List.of("powershell.exe", "pwsh")) {
            try {
                ProcessResult result = run(List.of(candidate, "-NoProfile", "-Command",
                        "$PSVersionTable.PSVersion.Major"));
                if (result.exitCode() == 0) {
                    available.add(candidate);
                }
            } catch (Exception ignored) {
                // Linux CI等ではstatic contractを実行し、test自体はskipしない。
            }
        }
        return available;
    }

    private ProcessResult run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("PowerShell helperが45秒以内に終了しませんでした");
        }
        return new ProcessResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void assertStaticCompatibilityContract() throws Exception {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        assertTrue(script.contains("PreflightOnly"));
        assertTrue(script.contains("MavenExecutable"));
        assertTrue(script.contains("Maven build/test failed"));
    }

    private record ProcessResult(int exitCode, String output) { }
}
