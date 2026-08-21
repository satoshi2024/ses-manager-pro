package com.ses.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FIND-I18N-01: SES.i18n.t の2引数 fallback を Node で固定する。
 */
class I18nTFallbackTest {

    private static final Path SCRIPT = Paths.get("src/test/resources/js/i18n-t-fallback-test.js");

    @Test
    void twoArgFallbackUsesTrailingStringWhenKeyMissing() throws Exception {
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            assertTrue(nodeAvailable(), "CI環境では i18n fallback 検査のための Node.js が必須です");
        } else {
            assumeTrue(nodeAvailable(), "Node.js が利用できないため i18n fallback 検査をskipします");
        }
        Process p = new ProcessBuilder("node", SCRIPT.toString())
                .redirectErrorStream(true)
                .directory(Paths.get(".").toFile())
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        assertEquals(0, code, out);
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
