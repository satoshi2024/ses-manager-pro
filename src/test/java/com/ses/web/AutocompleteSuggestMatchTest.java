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
 * サジェスト（{@code SES.autocomplete}）の候補絞り込みロジックの検査。
 *
 * <p>実体は Node.js スクリプト {@code src/test/resources/js/autocomplete-match-test.js} で、
 * common.js の {@code match()} を実データ（駅名8千件超）に対して直接呼び、
 * 「入力した文字で始まらない候補が上位に出る」不具合の再発を防ぐ。
 *
 * <p>{@link JsSyntaxCheckTest} と同じく Node.js が必要で、PATHに無い環境では自動skipし、
 * CI（環境変数 {@code CI=true}）では必須とする。
 */
class AutocompleteSuggestMatchTest {

    private static final Path SCRIPT = Paths.get("src/test/resources/js/autocomplete-match-test.js");

    @Test
    void suggestMatchesPrefixFirst() throws IOException, InterruptedException {
        if ("true".equals(System.getenv("CI"))) {
            assertTrue(nodeAvailable(), "CI環境では サジェスト検査のための Node.js が必須です");
        } else {
            assumeTrue(nodeAvailable(), "Node.js が利用できないため サジェスト検査をskipします");
        }
        assertTrue(Files.isRegularFile(SCRIPT), "検査スクリプトが見つかりません: " + SCRIPT);

        Process p = new ProcessBuilder("node", SCRIPT.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new AssertionError("サジェスト検査がタイムアウトしました");
        }
        assertEquals(0, p.exitValue(), "サジェストの候補絞り込みが期待どおりではありません:\n" + output);
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
