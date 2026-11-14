package com.ses.importcenter.metadata;

import java.util.List;
import java.util.Map;

/**
 * preview 1行。行番号・source行hash・判定・finding codeに加え、{@link ImportPreviewMasker}でマスクした値だけを返す。
 * 原文の個人情報（担当者名・連絡先・住所・備考）はマスク前の値を返さない。
 */
public record ImportPreviewRow(long sourceRowNumber, String sourceRowHash, String disposition,
                               List<String> findingCodes, Map<String, String> fields) {
    public ImportPreviewRow {
        findingCodes = findingCodes == null ? List.of() : List.copyOf(findingCodes);
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }
}
