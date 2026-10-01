package com.ses.importcenter.foundation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalDate;
/** source rowを5種類の不変canonical DTOへ写す。業務Entityへは変換しない。 */
public final class CanonicalRowFactory {
    private CanonicalRowFactory() { }
    public static CanonicalImportRow create(ImportEntity entity, SourceRow row) {
        String rawKey = row.values().get("externalKey");
        String key = rawKey == null || "\\N".equals(rawKey) ? null : rawKey;
        if (key != null) {
            try { key = ExternalKeyNormalizer.normalize(key); }
            catch (IllegalArgumentException ignored) { /* validatorへ原値を渡してfinding化する */ }
        }
        ImportRowMetadata metadata = new ImportRowMetadata(row.sourceRowNumber(), row.sourceRowHash(), key);
        Map<String, FieldValue<String>> fields = new LinkedHashMap<>();
        for (String header : SesImportV1Schema.definition(entity).allowedHeaders()) {
            if ("externalKey".equals(header)) continue;
            if (!row.values().containsKey(header)) fields.put(header, FieldValue.missing());
            else if ("\\N".equals(row.values().get(header))) fields.put(header, FieldValue.explicitNull());
            else fields.put(header, FieldValue.of(row.values().get(header)));
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (var field : fields.entrySet()) {
            if (field.getValue().presence() != FieldValue.Presence.VALUE || field.getValue().value().isBlank()) continue;
            String name = field.getKey(), value = field.getValue().value();
            try {
                if (isAmount(name)) {
                    ImportAmountParser.AmountResult amount = ImportAmountParser.parse(field.getValue());
                    if (amount.known()) typed.put(name, amount.value());
                } else if ("proposedAt".equals(name)) typed.put(name, java.time.LocalDateTime.parse(value));
                else if (isDate(name)) typed.put(name, LocalDate.parse(value));
                else if (isInteger(name)) typed.put(name, Integer.valueOf(value));
            } catch (RuntimeException ignored) {
                // 変換失敗の原値はfieldsに保持し、validatorがfindingへ分類する。
            }
        }
        return switch (entity) {
            case CUSTOMER -> new CustomerImportRow(metadata, fields, typed);
            case ENGINEER -> new EngineerImportRow(metadata, fields, typed);
            case PROJECT -> new ProjectImportRow(metadata, fields, typed);
            case PROPOSAL -> new ProposalImportRow(metadata, fields, typed);
            case CONTRACT -> new ContractImportRow(metadata, fields, typed);
        };
    }

    private static boolean isAmount(String field) { return java.util.Set.of("expectedUnitPrice", "unitPriceMin", "unitPriceMax", "sellingPrice", "costPrice").contains(field); }
    private static boolean isDate(String field) { return java.util.Set.of("birthDate", "startDate", "endDate").contains(field); }
    private static boolean isInteger(String field) { return java.util.Set.of("experienceYears", "requiredCount").contains(field); }
}
