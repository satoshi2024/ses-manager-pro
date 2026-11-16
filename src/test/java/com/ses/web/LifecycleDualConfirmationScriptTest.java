package com.ses.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * U14（NF01細粒度epoch計画 §6.2、W3-21b）: 二者確認の画面部品 {@code lifecycle-dual.js} の振る舞いをNodeで固定する。
 *
 * <p>検査内容は {@code src/test/resources/js/lifecycle-dual-test.js}: Idempotency-Keyの再利用と作り直し、CSRF header、
 * 二重送信の防止、版IDの無い提出を送らないこと（Q-9）、403・409でもサーバーのmessageを表示すること、本人画面に確認の経路が
 * 無いこと、本文を返されないcommentは「コメントあり」だけを表示すること。CIではNode必須（{@link I18nTFallbackTest} と同じ）。</p>
 */
class LifecycleDualConfirmationScriptTest {

    private static final Path SCRIPT = Paths.get("src/test/resources/js/lifecycle-dual-test.js");

    @Test
    void 二者確認の画面部品の送信と表示の規則をNodeで検査する() throws Exception {
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            assertTrue(nodeAvailable(), "CI環境では二者確認の画面部品の検査のための Node.js が必須です");
        } else {
            assumeTrue(nodeAvailable(), "Node.js が利用できないため二者確認の画面部品の検査をskipします");
        }
        Process process = new ProcessBuilder("node", SCRIPT.toString())
                .redirectErrorStream(true)
                .directory(Paths.get(".").toFile())
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertEquals(0, code, output);
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
