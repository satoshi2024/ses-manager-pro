package com.ses.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** security metadataを可逆にしないためのhash utility。 */
public final class SecurityHashUtil {

    private SecurityHashUtil() {
    }

    public static String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }
}
