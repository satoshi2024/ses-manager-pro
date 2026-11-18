package com.ses.importcenter.metadata;

import java.util.Map;

/**
 * mapping版の一覧応答。mappingは版ごとに変更不可で、「更新」は同じsource・entity・schemaの新しい版の登録になる。
 * {@code mappingHashPrefix}はSHA-256の先頭12桁だけ。
 */
public record ImportMappingView(long id, long sourceSystemId, String entity, String schemaVersion, int mappingVersion,
                                String mappingHashPrefix, Map<String, String> headerToCanonicalField) {
    public ImportMappingView {
        headerToCanonicalField = headerToCanonicalField == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(headerToCanonicalField));
    }
}
