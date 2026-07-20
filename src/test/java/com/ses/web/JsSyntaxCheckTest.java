package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 全JSモジュールの構文検査（R3R-36）。
 *
 * 画面JSの構文エラー（テンプレートリテラルの引用符欠落など、R3R-03/04で発生した種類）を
 * merge前に自動検出する。実行には Node.js が必要で、`node` が PATH に無い環境では自動skipする
 * （Testcontainers 版 migration smoke と同じ「利用可能なら検査、無ければskip」方針）。CI では
 * Node.js を用意することで実効化する。
 */
class JsSyntaxCheckTest {

    private static final Path JS_ROOT = Paths.get("src/main/resources/static/js");

    @Test
    void allJsModulesParse() throws IOException, InterruptedException {
        if ("true".equals(System.getenv("CI"))) {
            assertTrue(nodeAvailable(), "CI環境では JS 構文検査のための Node.js が必須です");
        } else {
            assumeTrue(nodeAvailable(), "Node.js が利用できないため JS 構文検査をskipします");
        }
        assumeTrue(Files.isDirectory(JS_ROOT), "JSディレクトリが存在しません: " + JS_ROOT);

        List<String> failures = new ArrayList<>();
        List<Path> jsFiles;
        try (Stream<Path> paths = Files.walk(JS_ROOT)) {
            jsFiles = paths.filter(p -> p.toString().endsWith(".js")).toList();
        }
        assertTrue(!jsFiles.isEmpty(), "検査対象のJSファイルが見つかりません");

        for (Path js : jsFiles) {
            Process p = new ProcessBuilder("node", "--check", js.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes());
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                failures.add(js + " : 検査がタイムアウトしました");
            } else if (p.exitValue() != 0) {
                failures.add(js + " : " + output.trim());
            }
        }

        assertTrue(failures.isEmpty(), "JS構文エラーを検出しました:\n" + String.join("\n", failures));
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
