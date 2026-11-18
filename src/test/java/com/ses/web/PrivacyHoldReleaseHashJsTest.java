package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * hold 画面の理由の hash（{@code privacy-holds.js} の Web Crypto 実装）が、deploy-runbook §4.5 の test vector と一致するか
 * （NF07-REL delta review P3-6。Java 側は {@code HoldReleaseProofVerifierTest} が同じ vector と照合する）。
 *
 * <p>実体は Node.js スクリプト {@code src/test/resources/js/privacy-hold-release-hash-test.js}。
 * {@link AutocompleteSuggestMatchTest} と同じく、PATH に Node.js が無い環境では自動 skip し、CI（{@code CI=true}）では必須とする。
 */
class PrivacyHoldReleaseHashJsTest {

    private static final Path SCRIPT = Paths.get("src/test/resources/js/privacy-hold-release-hash-test.js");

    @Test
    void 画面の理由のhashはrunbookのtest_vectorと一致する() throws IOException, InterruptedException {
        if ("true".equals(System.getenv("CI"))) {
            assertTrue(nodeAvailable(), "CI環境では 理由の hash の検査のための Node.js が必須です");
        } else {
            assumeTrue(nodeAvailable(), "Node.js が利用できないため 理由の hash の検査をskipします");
        }
        assertTrue(Files.isRegularFile(SCRIPT), "検査スクリプトが見つかりません: " + SCRIPT);

        Process p = new ProcessBuilder("node", SCRIPT.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new AssertionError("理由の hash の検査がタイムアウトしました");
        }
        assertEquals(0, p.exitValue(), "画面の理由の hash が test vector と一致しません:\n" + output);
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
