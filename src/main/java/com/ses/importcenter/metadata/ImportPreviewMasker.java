package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.FieldValue;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * previewに出すcanonical値のマスク規則（A1a）。分類表に無いfield・entityは全て完全マスクにする（fail-closed）。
 *
 * <ul>
 *   <li>{@code PLAIN}：業務上の識別・区分値（外部key、会社名・カナ、商流、信用度、納品希望）。64文字で切り詰める。</li>
 *   <li>{@code NAME}：担当者名。先頭1文字だけを残す（例: 「山＊＊」）。</li>
 *   <li>{@code EMAIL}：local部の先頭1文字とdomainだけを残す（例: 「a＊＊＊@example.co.jp」）。</li>
 *   <li>{@code PHONE}：数字の末尾4桁だけを残す（例: 「＊＊＊＊1234」）。</li>
 *   <li>{@code FULL}：住所・備考等の自由記述は値の有無だけを示す（「＊＊＊」）。</li>
 * </ul>
 * 値が空文字ならそのまま空文字、明示null（{@code \N}）はnull、列が無い項目は出さない。
 */
final class ImportPreviewMasker {
    static final String MASK = "＊＊＊";
    private static final int PLAIN_MAX = 64;

    enum Rule { PLAIN, NAME, EMAIL, PHONE, FULL }

    private static final Map<String, Rule> CUSTOMER_RULES = Map.ofEntries(
            Map.entry("externalKey", Rule.PLAIN),
            Map.entry("companyName", Rule.PLAIN),
            Map.entry("companyNameKana", Rule.PLAIN),
            Map.entry("contactPerson", Rule.NAME),
            Map.entry("contactEmail", Rule.EMAIL),
            Map.entry("contactPhone", Rule.PHONE),
            Map.entry("address", Rule.FULL),
            Map.entry("commercialFlow", Rule.PLAIN),
            Map.entry("trustLevel", Rule.PLAIN),
            Map.entry("deliveryPreference", Rule.PLAIN),
            Map.entry("remarks", Rule.FULL));

    private ImportPreviewMasker() {
    }

    static Rule rule(ImportEntity entity, String field) {
        if (entity != ImportEntity.CUSTOMER) return Rule.FULL;
        return CUSTOMER_RULES.getOrDefault(field, Rule.FULL);
    }

    /** schemaの列順でマスク済みの値を返す。{@code externalKey}は正規化前の原値を受け取る。 */
    static Map<String, String> mask(ImportEntity entity, String rawExternalKey,
                                    Map<String, FieldValue<String>> fields) {
        Map<String, String> masked = new LinkedHashMap<>();
        for (String header : SesImportV1Schema.definition(entity).allowedHeaders()) {
            if ("externalKey".equals(header)) {
                if (rawExternalKey != null) {
                    masked.put(header, "\\N".equals(rawExternalKey) ? null : apply(rule(entity, header), rawExternalKey));
                }
                continue;
            }
            FieldValue<String> value = fields == null ? null : fields.get(header);
            if (value == null || value.presence() == FieldValue.Presence.NOT_PROVIDED) continue;
            masked.put(header, value.presence() == FieldValue.Presence.EXPLICIT_NULL ? null
                    : apply(rule(entity, header), value.value()));
        }
        return Collections.unmodifiableMap(masked);
    }

    static String apply(Rule rule, String value) {
        if (value == null) return null;
        if (value.isEmpty()) return "";
        return switch (rule) {
            case PLAIN -> value.codePointCount(0, value.length()) <= PLAIN_MAX ? value
                    : value.substring(0, value.offsetByCodePoints(0, PLAIN_MAX)) + "…";
            case NAME -> new String(Character.toChars(value.strip().isEmpty() ? '＊' : value.strip().codePointAt(0)))
                    + "＊＊";
            case EMAIL -> maskEmail(value);
            case PHONE -> maskPhone(value);
            case FULL -> MASK;
        };
    }

    private static String maskEmail(String value) {
        String trimmed = value.strip();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) return MASK;
        String domain = trimmed.substring(at + 1);
        if (domain.length() > PLAIN_MAX) return MASK;
        return new String(Character.toChars(trimmed.codePointAt(0))) + "＊＊＊@" + domain;
    }

    private static String maskPhone(String value) {
        StringBuilder digits = new StringBuilder();
        value.codePoints().filter(Character::isDigit).forEach(digits::appendCodePoint);
        if (digits.length() < 4) return MASK;
        return "＊＊＊＊" + digits.substring(digits.length() - 4);
    }
}
