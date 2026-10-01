package com.ses.importcenter.foundation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** DB no-writeのfield/reference/cross-row validator。業務serviceを呼び出さない。 */
public final class ImportValidationService {
    private static final Set<String> EMPLOYMENT = Set.of("正社員", "契約社員", "BP");
    private static final Set<String> ENGINEER_STATUS = Set.of("Bench");
    private static final Set<String> PROJECT_STATUS = Set.of("募集中");
    private static final Set<String> PROPOSAL_STATUS = Set.of("書類選考中");
    private static final Set<String> CONTRACT_STATUS = Set.of("準備中");

    public ValidatedRow validate(CanonicalImportRow row, ValidationContext context, ImportValidationSession session) {
        List<ImportFinding> findings = new ArrayList<>();
        ImportRowMetadata metadata = row.metadata();
        if (metadata.externalKey() == null) add(findings, metadata, "EXTERNAL_KEY_REQUIRED", "externalKeyは必須です");
        else {
            try {
                String normalized = ExternalKeyNormalizer.normalize(metadata.externalKey());
                if (!session.firstKey(row.entity(), normalized)) add(findings, metadata, "DUPLICATE_SOURCE_KEY", "source内externalKeyが重複しています");
            } catch (IllegalArgumentException exception) {
                add(findings, metadata, "EXTERNAL_KEY_INVALID", "externalKeyの形式が不正です");
            }
        }
        validateFields(row, findings);
        resolveRelations(row, context, findings);
        java.util.Map<String, BigDecimal> amounts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> excluded = new java.util.LinkedHashMap<>();
        for (String amountField : amountFields(row.entity())) {
            FieldValue<String> value = field(row, amountField);
            ImportAmountParser.AmountResult parsed = ImportAmountParser.parse(value);
            if (parsed.known()) amounts.put(amountField, parsed.value());
            else {
                excluded.put(amountField, parsed.excludedReason());
                if (requiredAmount(row.entity(), amountField)) add(findings, metadata, "AMOUNT_REQUIRED", amountField + "はJPY整数で必須です");
                else if (value.presence() == FieldValue.Presence.VALUE && !value.value().isBlank()) add(findings, metadata, parsed.excludedReason(), amountField + "はJPY scale=0/UNNECESSARYに適合しません");
            }
        }
        return new ValidatedRow(row, findings, amounts, excluded);
    }

    private static void validateFields(CanonicalImportRow row, List<ImportFinding> findings) {
        ImportRowMetadata metadata = row.metadata();
        switch (row.entity()) {
            case CUSTOMER -> {
                required(row, "companyName", findings);
                length(row, "companyName", 100, findings); length(row, "companyNameKana", 100, findings);
                length(row, "contactPerson", 50, findings); length(row, "contactEmail", 255, findings);
                length(row, "contactPhone", 20, findings); length(row, "address", 255, findings);
                length(row, "commercialFlow", 20, findings); length(row, "trustLevel", 1, findings);
                length(row, "deliveryPreference", 20, findings); length(row, "remarks", 1000, findings);
                email(row, findings);
            }
            case ENGINEER -> {
                required(row, "fullName", findings);
                allow(row, "employmentType", EMPLOYMENT, findings);
                allow(row, "status", ENGINEER_STATUS, findings);
                allow(row, "gender", Set.of("男性", "女性"), findings);
                allow(row, "japaneseLevel", Set.of("ネイティブ", "ビジネスレベル", "日常会話レベル", "基礎レベル", "不問"), findings);
                integer(row, "experienceYears", false, findings);
                date(row, "birthDate", findings);
                length(row, "japaneseLevel", 20, findings); length(row, "nationality", 50, findings);
                length(row, "remarks", 4000, findings);
            }
            case PROJECT -> {
                required(row, "projectName", findings);
                allow(row, "status", PROJECT_STATUS, findings);
                integer(row, "requiredCount", false, findings);
                date(row, "startDate", findings); date(row, "endDate", findings);
                dateRange(row, "startDate", "endDate", findings);
                compareAmounts(row, "unitPriceMin", "unitPriceMax", findings);
                length(row, "projectName", 255, findings); length(row, "description", 4000, findings);
            }
            case PROPOSAL -> {
                allow(row, "status", PROPOSAL_STATUS, findings);
                dateTime(row, "proposedAt", findings);
                length(row, "remarks", 4000, findings);
            }
            case CONTRACT -> {
                allow(row, "status", CONTRACT_STATUS, findings);
                date(row, "startDate", findings); date(row, "endDate", findings);
                dateRange(row, "startDate", "endDate", findings);
                length(row, "contractType", 50, findings); length(row, "remarks", 4000, findings);
            }
        }
    }

    private static void resolveRelations(CanonicalImportRow row, ValidationContext context, List<ImportFinding> findings) {
        if (row.entity() == ImportEntity.ENGINEER) {
            resolveOptional(row, context, "organizationExternalKey", ReferenceType.ORGANIZATION, findings);
            resolveOptional(row, context, "costCenterExternalKey", ReferenceType.COST_CENTER, findings);
        }
        if (row.entity() == ImportEntity.PROJECT) resolve(row, context, "customerExternalKey", ReferenceType.CUSTOMER, findings);
        if (row.entity() == ImportEntity.PROPOSAL) {
            resolve(row, context, "engineerExternalKey", ReferenceType.ENGINEER, findings);
            resolve(row, context, "projectExternalKey", ReferenceType.PROJECT, findings);
            resolveOptional(row, context, "positionExternalKey", ReferenceType.POSITION, findings);
        }
        if (row.entity() == ImportEntity.CONTRACT) {
            ReferenceTarget customer = resolve(row, context, "customerExternalKey", ReferenceType.CUSTOMER, findings);
            resolve(row, context, "engineerExternalKey", ReferenceType.ENGINEER, findings);
            ReferenceTarget project = resolve(row, context, "projectExternalKey", ReferenceType.PROJECT, findings);
            if (customer != null && project != null) {
                String projectCustomer = project.relationKeys().get("customerExternalKey");
                String importedCustomer = field(row, "customerExternalKey").value();
                if (projectCustomer == null) add(findings, row.metadata(), "RELATIONSHIP_UNVERIFIABLE", "projectとcustomerの関連を検証できません");
                else {
                    try {
                        if (!ExternalKeyNormalizer.normalize(projectCustomer).equals(ExternalKeyNormalizer.normalize(importedCustomer))) {
                            add(findings, row.metadata(), "RELATIONSHIP_MISMATCH", "contractのcustomerとprojectのcustomerが一致しません");
                        }
                    } catch (IllegalArgumentException invalidRelationKey) {
                        add(findings, row.metadata(), "RELATIONSHIP_UNVERIFIABLE", "projectのcustomer externalKeyが不正です");
                    }
                }
            }
        }
    }

    private static ReferenceTarget resolve(CanonicalImportRow row, ValidationContext context, String fieldName,
                                ReferenceType referenceType, List<ImportFinding> findings) {
        FieldValue<String> value = field(row, fieldName);
        if (value.presence() != FieldValue.Presence.VALUE || value.value().isBlank()) {
            if (row.entity() == ImportEntity.PROPOSAL && "positionExternalKey".equals(fieldName)) return null;
            add(findings, row.metadata(), "REFERENCE_REQUIRED", fieldName + "は必須です"); return null;
        }
        String key;
        try { key = ExternalKeyNormalizer.normalize(value.value()); }
        catch (IllegalArgumentException exception) { add(findings, row.metadata(), "REFERENCE_KEY_INVALID", fieldName + "のexternalKeyが不正です"); return null; }
        ReferenceLookup lookup = context.resolver().resolve(referenceType, context.tenantId(), context.legalEntityId(), key);
        if (lookup == null || lookup.status() == null) {
            add(findings, row.metadata(), "REFERENCE_LOOKUP_UNKNOWN", fieldName + "の照合結果を確認できません");
            return null;
        }
        if (lookup.status() != LookupStatus.UNIQUE_ACTIVE || lookup.candidates().size() != 1) {
            String code = switch (lookup.status()) {
                case NOT_FOUND -> "REFERENCE_NOT_FOUND";
                case LOGICALLY_DELETED -> "REFERENCE_DELETED";
                case AMBIGUOUS -> "REFERENCE_AMBIGUOUS";
                case OUT_OF_SCOPE -> "REFERENCE_OUT_OF_SCOPE";
                case UNIQUE_ACTIVE -> "REFERENCE_INVALID";
            };
            add(findings, row.metadata(), code, fieldName + "を一意な同一scopeのactive rowへ解決できません");
            return null;
        } else {
            ReferenceTarget target = lookup.candidates().get(0);
            if (!context.tenantId().equals(target.tenantId()) || !context.legalEntityId().equals(target.legalEntityId()) || !target.active()) {
                add(findings, row.metadata(), "REFERENCE_OUT_OF_SCOPE", fieldName + "が実行scope外です");
                return null;
            }
            if (target.externalKey() == null || !key.equals(target.externalKey())) {
                add(findings, row.metadata(), "REFERENCE_IDENTITY_MISMATCH", fieldName + "の照合identityが一致しません");
                return null;
            }
            return target;
        }
    }

    private static void resolveOptional(CanonicalImportRow row, ValidationContext context, String fieldName,
                                        ReferenceType referenceType, List<ImportFinding> findings) {
        FieldValue<String> value = field(row, fieldName);
        if (value.presence() == FieldValue.Presence.VALUE && !value.value().isBlank()) {
            resolve(row, context, fieldName, referenceType, findings);
        }
    }

    private static void required(CanonicalImportRow row, String name, List<ImportFinding> findings) {
        FieldValue<String> value = field(row, name);
        if (value.presence() != FieldValue.Presence.VALUE || value.value().isBlank()) add(findings, row.metadata(), "FIELD_REQUIRED", name + "は必須です");
    }
    private static void length(CanonicalImportRow row, String name, int maximum, List<ImportFinding> findings) {
        FieldValue<String> value = field(row, name);
        if (value.presence() == FieldValue.Presence.VALUE && value.value().length() > maximum) {
            add(findings, row.metadata(), "FIELD_LENGTH_EXCEEDED", name + "は" + maximum + "文字以内で指定してください");
        }
    }
    private static void email(CanonicalImportRow row, List<ImportFinding> findings) {
        FieldValue<String> value = field(row, "contactEmail");
        if (value.presence() == FieldValue.Presence.VALUE && !value.value().isBlank()
                && !value.value().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            add(findings, row.metadata(), "EMAIL_INVALID", "contactEmailの形式が正しくありません");
        }
    }
    private static void allow(CanonicalImportRow row, String name, Set<String> allowed, List<ImportFinding> findings) {
        FieldValue<String> value = field(row, name);
        if (value.presence() == FieldValue.Presence.VALUE && !value.value().isBlank() && !allowed.contains(value.value())) {
            add(findings, row.metadata(), "STATE_OR_ENUM_NOT_ALLOWED", name + "の値はses-import-v1で許可されていません");
        }
    }
    private static void integer(CanonicalImportRow row, String name, boolean required, List<ImportFinding> findings) {
        FieldValue<String> value = field(row, name);
        if (value.presence() != FieldValue.Presence.VALUE || value.value().isBlank()) {
            if (required) add(findings, row.metadata(), "FIELD_REQUIRED", name + "は必須です");
            return;
        }
        try { if (Integer.parseInt(value.value()) < 0) throw new NumberFormatException(); }
        catch (NumberFormatException e) { add(findings, row.metadata(), "INTEGER_INVALID", name + "は0以上の整数で指定してください"); }
    }
    private static void date(CanonicalImportRow row, String name, List<ImportFinding> findings) {
        FieldValue<String> value = field(row, name);
        if (value.presence() == FieldValue.Presence.VALUE && !value.value().isBlank()) {
            try { LocalDate.parse(value.value()); }
            catch (DateTimeParseException e) { add(findings, row.metadata(), "DATE_INVALID", name + "はISO-8601日付で指定してください"); }
        }
    }
    private static void dateTime(CanonicalImportRow row, String name, List<ImportFinding> findings) {
        FieldValue<String> value = field(row, name);
        if (value.presence() == FieldValue.Presence.VALUE && !value.value().isBlank()) {
            try { java.time.LocalDateTime.parse(value.value()); }
            catch (java.time.format.DateTimeParseException e) { add(findings, row.metadata(), "DATETIME_INVALID", name + "はISO-8601日時で指定してください"); }
        }
    }
    private static void dateRange(CanonicalImportRow row, String startName, String endName, List<ImportFinding> findings) {
        FieldValue<String> start = field(row, startName), end = field(row, endName);
        if (start.presence() == FieldValue.Presence.VALUE && end.presence() == FieldValue.Presence.VALUE
                && !start.value().isBlank() && !end.value().isBlank()) {
            try { if (LocalDate.parse(end.value()).isBefore(LocalDate.parse(start.value()))) add(findings, row.metadata(), "DATE_RANGE_INVALID", "終了日は開始日以降で指定してください"); }
            catch (DateTimeParseException ignored) { }
        }
    }
    private static void compareAmounts(CanonicalImportRow row, String minimum, String maximum, List<ImportFinding> findings) {
        var min = ImportAmountParser.parse(field(row, minimum)); var max = ImportAmountParser.parse(field(row, maximum));
        if (min.known() && max.known() && min.value().compareTo(max.value()) > 0) add(findings, row.metadata(), "AMOUNT_RANGE_INVALID", "単価上限は下限以上で指定してください");
    }
    private static FieldValue<String> field(CanonicalImportRow row, String name) { return row.fields().getOrDefault(name, FieldValue.missing()); }
    private static List<String> amountFields(ImportEntity entity) {
        return switch (entity) { case ENGINEER -> List.of("expectedUnitPrice"); case PROJECT -> List.of("unitPriceMin", "unitPriceMax"); case CONTRACT -> List.of("sellingPrice", "costPrice"); default -> List.of(); };
    }
    private static boolean requiredAmount(ImportEntity entity, String name) { return entity == ImportEntity.CONTRACT; }
    private static void add(List<ImportFinding> findings, ImportRowMetadata metadata, String code, String message) {
        findings.add(new ImportFinding(metadata.sourceRowNumber(), metadata.sourceRowHash(), code, message));
    }
}
