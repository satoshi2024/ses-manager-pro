package com.ses.importcenter.metadata;

import java.util.List;
import java.util.Map;

/**
 * preview 1行。行番号・行参照・判定・finding codeに加え、{@link ImportPreviewMasker}でマスクした値だけを返す。
 * 原文の個人情報（担当者名・連絡先・住所・備考）はマスク前の値を返さない。
 *
 * <p>{@code sourceRowHash} は保存済みsource行hash（鍵の無いSHA-256）ではなく、鍵付きHMACの行参照
 * （{@link ImportSourceRowReference}）である。同じjobのerror exportの値と一致し、平文から計算し直すことはできない。
 */
public record ImportPreviewRow(long sourceRowNumber, String sourceRowHash, String disposition,
                               List<String> findingCodes, Map<String, String> fields) {
    public ImportPreviewRow {
        findingCodes = findingCodes == null ? List.of() : List.copyOf(findingCodes);
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }
}
