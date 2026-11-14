package com.ses.importcenter.foundation;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** ses-key-v1: NFC、大小文字保持、端空白拒否、binary比較を前提とする。 */
public final class ExternalKeyNormalizer {
    public static final int MAX_UTF8_BYTES = 256;
    private ExternalKeyNormalizer() { }
    public static String normalize(String input) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("externalKeyは必須です");
        if (!input.equals(input.strip())) throw new IllegalArgumentException("externalKeyの前後空白は許可されません");
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);
        if (normalized.isEmpty() || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("externalKeyが空またはbyte上限超過です");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("externalKeyに制御文字は使えません");
        return normalized;
    }
}
