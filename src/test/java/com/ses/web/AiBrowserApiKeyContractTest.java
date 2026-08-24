package com.ses.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2-3 (ACC-SEC-P1-004 / REV-P1-003): ブラウザから Gemini API キーを扱わないことのフロントエンド契約テスト。
 *
 * <p>AIチャット画面（templates/ai/matching.html）と静的JS（static/js/modules/ai.js）から、
 * APIキー入力欄・sessionStorage/localStorage への保存・AJAXボディへのAPIキー送信が
 * 完全に取り除かれていることを固定する。サーバーは {@code ai.api-key} 設定のみを使用する。
 */
class AiBrowserApiKeyContractTest {

    private String read(String classpathLocation) throws Exception {
        Resource resource = new PathMatchingResourcePatternResolver().getResource(classpathLocation);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void matchingテンプレートにAPIキー入力欄が存在しない() throws Exception {
        String html = read("classpath:templates/ai/matching.html");
        assertFalse(html.contains("geminiApiKey"), "APIキー入力欄(geminiApiKey)を画面から削除すること");
        assertFalse(html.contains("Gemini API Key"), "APIキー入力ラベルを削除すること");
    }

    @Test
    void aiJsはAPIキーの読み取り保存送信を行わない() throws Exception {
        String js = read("classpath:static/js/modules/ai.js");
        assertFalse(js.contains("geminiApiKey"), "APIキー入力欄の参照を削除すること");
        assertFalse(js.contains("sessionStorage"), "APIキーの sessionStorage 保存を削除すること");
        assertFalse(js.contains("localStorage"), "APIキーの localStorage 保存を削除すること");
        assertFalse(js.contains("apiKey"), "AJAXボディへの apiKey 送信を削除すること");
    }

    @Test
    void aiJsはチャット用のprompt送信を維持している() throws Exception {
        // 機能自体は維持する（回帰防止）。
        String js = read("classpath:static/js/modules/ai.js");
        assertTrue(js.contains("/api/ai/chat"), "チャットAPI呼び出しは維持すること");
        assertTrue(js.contains("prompt:"), "prompt の送信は維持すること");
    }
}
