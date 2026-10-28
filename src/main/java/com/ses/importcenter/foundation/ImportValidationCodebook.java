package com.ses.importcenter.foundation;

import java.util.List;
import java.util.Set;

/** F1bのfinding/reason/amount fieldを閉じたcode setとして共有する。 */
public final class ImportValidationCodebook {
    static final String AMOUNT_UNKNOWN_OR_EMPTY = "AMOUNT_UNKNOWN_OR_EMPTY";
    static final String AMOUNT_PRECISION_EXCEEDED = "AMOUNT_PRECISION_EXCEEDED";
    static final String AMOUNT_INVALID_OR_FRACTIONAL_YEN = "AMOUNT_INVALID_OR_FRACTIONAL_YEN";
    static final String AMOUNT_NEGATIVE_NOT_ALLOWED = "AMOUNT_NEGATIVE_NOT_ALLOWED";
    private static final Set<String> FINDING_CODES = Set.of(
            AMOUNT_UNKNOWN_OR_EMPTY, AMOUNT_PRECISION_EXCEEDED, AMOUNT_INVALID_OR_FRACTIONAL_YEN,
            AMOUNT_NEGATIVE_NOT_ALLOWED, "AMOUNT_RANGE_INVALID", "AMOUNT_REQUIRED", "CANONICAL_CONVERSION_FAILED",
            "DATE_INVALID", "DATE_RANGE_INVALID", "DATETIME_INVALID", "DUPLICATE_SOURCE_KEY", "EMAIL_INVALID",
            "EXTERNAL_KEY_INVALID", "EXTERNAL_KEY_REQUIRED", "FIELD_LENGTH_EXCEEDED", "FIELD_REQUIRED",
            "INTEGER_INVALID", "REFERENCE_AMBIGUOUS", "REFERENCE_DELETED", "REFERENCE_IDENTITY_MISMATCH",
            "REFERENCE_INVALID", "REFERENCE_KEY_INVALID", "REFERENCE_LOOKUP_UNKNOWN", "REFERENCE_NOT_FOUND",
            "REFERENCE_OUT_OF_SCOPE", "REFERENCE_REQUIRED", "RELATIONSHIP_MISMATCH", "RELATIONSHIP_UNVERIFIABLE",
            "STATE_OR_ENUM_NOT_ALLOWED");
    private static final Set<String> AMOUNT_EXCLUDED_REASONS = Set.of(
            AMOUNT_UNKNOWN_OR_EMPTY, AMOUNT_PRECISION_EXCEEDED,
            AMOUNT_INVALID_OR_FRACTIONAL_YEN, AMOUNT_NEGATIVE_NOT_ALLOWED);
    private static final List<String> ALL_AMOUNT_FIELDS = List.of(
            "expectedUnitPrice", "unitPriceMin", "unitPriceMax", "sellingPrice", "costPrice");

    private ImportValidationCodebook() { }

    public static boolean isFindingCode(String code) { return code != null && FINDING_CODES.contains(code); }

    static boolean isAmountExcludedReason(String reason) {
        return reason != null && AMOUNT_EXCLUDED_REASONS.contains(reason);
    }

    static List<String> allAmountFields() { return ALL_AMOUNT_FIELDS; }

    static List<String> amountFields(ImportEntity entity) {
        return switch (entity) {
            case ENGINEER -> List.of("expectedUnitPrice");
            case PROJECT -> List.of("unitPriceMin", "unitPriceMax");
            case CONTRACT -> List.of("sellingPrice", "costPrice");
            case CUSTOMER, PROPOSAL -> List.of();
        };
    }
}
