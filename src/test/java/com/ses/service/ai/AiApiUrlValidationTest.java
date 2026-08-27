package com.ses.service.ai;

import com.ses.common.security.OutboundUrlException;
import com.ses.common.security.OutboundUrlGuard;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AIエンドポイントURLのallowlist検証テスト（B2-2）。
 * Geminiの許可ホスト完全一致・HTTPS・443固定、サブドメイン詐称/別名/IPリテラル/userinfoの拒否を確認する。
 * 本テストは本番allowlistをそのまま用いる（MockServer向けに緩めない）。
 */
class AiApiUrlValidationTest {

    private static final Set<String> ALLOWED = Set.of("generativelanguage.googleapis.com");

    private final OutboundUrlGuard guard = new OutboundUrlGuard();

    @Test
    void 正規のGeminiエンドポイントは許可される() {
        assertThatCode(() -> guard.validateExactHostHttpsUrl(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent", ALLOWED))
                .doesNotThrowAnyException();
    }

    @Test
    void ポート443明示は許可される() {
        assertThatCode(() -> guard.validateExactHostHttpsUrl(
                "https://generativelanguage.googleapis.com:443/v1beta/models/x:generateContent", ALLOWED))
                .doesNotThrowAnyException();
    }

    @Test
    void HTTPは拒否される() {
        assertThatThrownBy(() -> guard.validateExactHostHttpsUrl(
                "http://generativelanguage.googleapis.com/v1beta/models/x:generateContent", ALLOWED))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void サブドメイン詐称は拒否される() {
        assertThatThrownBy(() -> guard.validateExactHostHttpsUrl(
                "https://generativelanguage.googleapis.com.evil.example/v1beta/x:generateContent", ALLOWED))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void 前方に別ラベルを付けたホストは拒否される() {
        assertThatThrownBy(() -> guard.validateExactHostHttpsUrl(
                "https://evil.generativelanguage.googleapis.com/v1beta/x:generateContent", ALLOWED))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void 別ホストは拒否される() {
        assertThatThrownBy(() -> guard.validateExactHostHttpsUrl(
                "https://evil.example.com/v1beta/models/x:generateContent", ALLOWED))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void userinfoによる詐称は拒否される() {
        assertThatThrownBy(() -> guard.validateExactHostHttpsUrl(
                "https://generativelanguage.googleapis.com@evil.example/x", ALLOWED))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPリテラルは拒否される() {
        assertThatThrownBy(() -> guard.validateExactHostHttpsUrl(
                "https://142.250.0.0/v1beta/models/x:generateContent", ALLOWED))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void 非443ポートは拒否される() {
        assertThatThrownBy(() -> guard.validateExactHostHttpsUrl(
                "https://generativelanguage.googleapis.com:8443/v1beta/x:generateContent", ALLOWED))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void 大文字ホストも正規化して許可される() {
        assertThatCode(() -> guard.validateExactHostHttpsUrl(
                "https://GenerativeLanguage.GoogleAPIs.com/v1beta/models/x:generateContent", ALLOWED))
                .doesNotThrowAnyException();
    }
}
