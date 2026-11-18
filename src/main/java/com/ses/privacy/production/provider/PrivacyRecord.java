package com.ses.privacy.production.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * provider が返す1行（allow-list の列だけ）。
 *
 * <p>論理削除済みの行も返し、{@code logicallyDeleted} で示す（論理削除は実体を消さないため、DSAR の検索と hold の
 * 帰属判定の母集団に含める）。{@code fields} の値は表の値そのもので、第三者情報の除去は
 * {@code ThirdPartyRedactor} が交付前に行う。
 *
 * @param providerId       provider ID
 * @param table            表名
 * @param rowId            行の主キー
 * @param dataElementIds   行が属する data element
 * @param logicallyDeleted 論理削除済みか
 * @param fields           allow-list の列と値（SELECT の順序）
 * @param storedNames      行が参照する保存名（実体 file）
 */
public record PrivacyRecord(String providerId, String table, long rowId, List<String> dataElementIds,
                            boolean logicallyDeleted, Map<String, Object> fields, Set<String> storedNames) {

    public PrivacyRecord {
        Objects.requireNonNull(providerId, "provider ID が必要です");
        Objects.requireNonNull(table, "表名が必要です");
        dataElementIds = List.copyOf(dataElementIds);
        // 値は NULL を含み得るため Map.copyOf は使わない
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        storedNames = Collections.unmodifiableSet(new LinkedHashSet<>(storedNames));
    }
}
