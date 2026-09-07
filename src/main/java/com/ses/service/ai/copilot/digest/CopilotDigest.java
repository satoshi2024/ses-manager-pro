package com.ses.service.ai.copilot.digest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * management copilot 向けの一方向 digest。SHA-256 失敗時は fail-closed で {@link IllegalStateException} を投げる。
 */
public final class CopilotDigest {

    private CopilotDigest() {
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 digest の計算に失敗しました", ex);
        }
    }
}
