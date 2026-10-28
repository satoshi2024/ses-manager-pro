package com.ses.importcenter.foundation;

import java.math.BigDecimal;

/** JPYはscale=0/UNNECESSARY固定。欠落・空値は既知の0円に変換しない。 */
public final class ImportAmountParser {
    private static final int MAX_INPUT_CHARS = 64;
    private static final java.util.regex.Pattern DECIMAL = java.util.regex.Pattern.compile("(-?)([0-9]+)(?:\\.([0-9]+))?");
    private ImportAmountParser() { }
    public static AmountResult parse(FieldValue<String> field) {
        if (field.presence() != FieldValue.Presence.VALUE || field.value().isBlank()) {
            return new AmountResult(null, ImportValidationCodebook.AMOUNT_UNKNOWN_OR_EMPTY);
        }
        String raw = field.value();
        if (raw.length() > MAX_INPUT_CHARS) return new AmountResult(null, ImportValidationCodebook.AMOUNT_PRECISION_EXCEEDED);
        java.util.regex.Matcher matcher = DECIMAL.matcher(raw);
        if (!matcher.matches()) return new AmountResult(null, ImportValidationCodebook.AMOUNT_INVALID_OR_FRACTIONAL_YEN);
        String fraction = matcher.group(3);
        if (fraction != null && fraction.chars().anyMatch(character -> character != '0')) {
            return new AmountResult(null, ImportValidationCodebook.AMOUNT_INVALID_OR_FRACTIONAL_YEN);
        }
        String digits = matcher.group(2).replaceFirst("^0+", "");
        if (digits.length() > 18) return new AmountResult(null, ImportValidationCodebook.AMOUNT_PRECISION_EXCEEDED);
        if ("-".equals(matcher.group(1)) && digits.chars().anyMatch(character -> character != '0')) {
            return new AmountResult(null, ImportValidationCodebook.AMOUNT_NEGATIVE_NOT_ALLOWED);
        }
        // 正規表現と長さ・有効桁数を先に検証し、BigDecimalへ巨大指数等を渡さない。
        return new AmountResult(new BigDecimal(digits.isEmpty() ? "0" : digits), null);
    }
    public record AmountResult(BigDecimal value, String excludedReason) {
        public boolean known() { return value != null; }
    }
}
