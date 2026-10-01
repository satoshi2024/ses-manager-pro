package com.ses.importcenter.foundation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** JPYはscale=0/UNNECESSARY固定。欠落・空値は既知の0円に変換しない。 */
public final class ImportAmountParser {
    private ImportAmountParser() { }
    public static AmountResult parse(FieldValue<String> field) {
        if (field.presence() != FieldValue.Presence.VALUE || field.value().isBlank()) {
            return new AmountResult(null, "AMOUNT_UNKNOWN_OR_EMPTY");
        }
        try {
            BigDecimal value = new BigDecimal(field.value());
            BigDecimal integer = value.setScale(0, RoundingMode.UNNECESSARY);
            if (integer.precision() > 18) return new AmountResult(null, "AMOUNT_PRECISION_EXCEEDED");
            if (integer.signum() < 0) return new AmountResult(null, "AMOUNT_NEGATIVE_NOT_ALLOWED");
            return new AmountResult(integer, null);
        } catch (NumberFormatException | ArithmeticException exception) {
            return new AmountResult(null, "AMOUNT_INVALID_OR_FRACTIONAL_YEN");
        }
    }
    public record AmountResult(BigDecimal value, String excludedReason) {
        public boolean known() { return value != null; }
    }
}
