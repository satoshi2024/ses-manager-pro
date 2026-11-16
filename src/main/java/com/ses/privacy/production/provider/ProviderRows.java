package com.ses.privacy.production.provider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** provider 共通の行変換（状態を持たない）。 */
final class ProviderRows {

    private ProviderRows() {
    }

    /** tenant を必須にする（空・空白は拒否）。 */
    static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant の指定が必要です");
        }
        return tenantId;
    }

    /** tenant と subject ID の bind 値。 */
    static Map<String, Object> params(String tenantId, PrivacySubjectRef subject) {
        return Map.of("tenantId", requireTenant(tenantId), "subjectId", subject.id());
    }

    /**
     * 1行を record にする。
     *
     * @param includeStoredNames 保存名の列を実体 file として扱うか（貼付け取込など実体の無い行は false）
     */
    static PrivacyRecord toRecord(String providerId, ProviderTable table, Map<String, Object> row,
                                  List<String> dataElementIds, boolean includeStoredNames) {
        Set<String> storedNames = new LinkedHashSet<>();
        if (includeStoredNames) {
            for (String column : table.storedNameColumns()) {
                String value = text(row.get(column));
                if (value != null) {
                    storedNames.add(value);
                }
            }
        }
        return new PrivacyRecord(providerId, table.table(), rowId(row), dataElementIds, deleted(row), row, storedNames);
    }

    /** 空白でない文字列値（それ以外は null）。 */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long rowId(Map<String, Object> row) {
        Long id = longValue(row.get("id"));
        if (id == null) {
            throw new IllegalStateException("行の id を読めません");
        }
        return id;
    }

    private static boolean deleted(Map<String, Object> row) {
        Object value = row.get("deleted_flag");
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof Number number && number.intValue() != 0;
    }
}
