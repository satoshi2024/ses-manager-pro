package com.ses.web;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** NF04の既存Node検査スクリプトを通常のfast gateから実行する。 */
class PwaBoundaryScriptGateTest {
    @Test
    void NF04の境界スクリプトが成功する() throws Exception {
        runNodeScript("scripts/test-nf04-pwa-boundaries.mjs", "NF04境界検査");
    }

    @Test
    void NF04の削除完了barrierスクリプトが成功する() throws Exception {
        runNodeScript("scripts/test-nf04-delete-completion-barrier.mjs", "NF04削除完了barrier検査");
    }

    private void runNodeScript(String path, String label) throws Exception {
        assumeTrue(nodeAvailable(), "Node.jsがPATHに無いため" + label + "をskipします");
        Path script = Path.of(path);
        assertTrue(Files.isRegularFile(script), label + "のスクリプトがありません: " + path);
        Path output = Files.createTempFile("nf04-script-output-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder("node", script.toString()).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), label + "が60秒で終了しません");
            assertEquals(0, process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            Files.deleteIfExists(output);
        }
    }

    private boolean nodeAvailable() throws InterruptedException {
        Process process = null;
        try {
            process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException absent) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
