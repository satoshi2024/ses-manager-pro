package com.ses.common.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * CRMの重複候補照合キー。Lead / CustomerContact で同一規則を共有する。
 * trim → NFKC → lower → (会社名は空白除去 / 連絡先は a-z0-9+@. 以外除去)。
 */
public final class CrmNormalize {

    private CrmNormalize() {
    }

    /** 会社名用キー。空白のみのゆらぎを吸収する。 */
    public static String companyKey(String value) {
        return searchKey(value, true);
    }

    /** email / phone 用キー。全角英数・記号ゆらぎを吸収する。 */
    public static String contactKey(String value) {
        return searchKey(value, false);
    }

    public static String searchKey(String value, boolean company) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        String key = company
                ? normalized.replaceAll("\\s+", "")
                : normalized.replaceAll("[^a-z0-9+@.]", "");
        return key.isEmpty() ? null : key;
    }
}
