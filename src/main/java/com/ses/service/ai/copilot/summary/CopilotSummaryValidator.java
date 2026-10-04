package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.AiGatewayRequest;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** summary text / claimKeys の契約検証。 */
public final class CopilotSummaryValidator {

    private static final Pattern HTML = Pattern.compile("(?i)<[^>]+>");
    private static final Set<Integer> NUMBER_TYPES = Set.of(
            (int) Character.DECIMAL_DIGIT_NUMBER,
            (int) Character.LETTER_NUMBER,
            (int) Character.OTHER_NUMBER);
    private static final String KANJI_NUMERALS = "〇零一二三四五六七八九十百千万億兆京垓"
            + "壱弐参肆伍陸漆捌玖拾佰阡仟萬";
    private static final Set<Integer> PERCENT_SYMBOLS = Set.of(
            (int) '%', 0x066A, 0x0609, 0x060A, 0x2030, 0x2031, 0xFF05);

    private CopilotSummaryValidator() {
    }

    public static void validateSummaryText(String summaryText) {
        if (summaryText == null || summaryText.isBlank()) {
            throw new CopilotSummaryValidationException("EMPTY_SUMMARY");
        }
        if (HTML.matcher(summaryText).find()) {
            throw new CopilotSummaryValidationException("HTML_REJECTED");
        }
        if (summaryText.contains(AiGatewayRequest.CANARY)) {
            throw new CopilotSummaryValidationException("PII_CANARY");
        }
        if (containsNumericClaim(summaryText)) {
            throw new CopilotSummaryValidationException("NUMERIC_RECALC_REJECTED");
        }
    }

    private static boolean containsNumericClaim(String summaryText) {
        for (int offset = 0; offset < summaryText.length();) {
            int codePoint = summaryText.codePointAt(offset);
            int category = Character.getType(codePoint);
            if (NUMBER_TYPES.contains(category)
                    || category == Character.CURRENCY_SYMBOL
                    || PERCENT_SYMBOLS.contains(codePoint)
                    || KANJI_NUMERALS.indexOf(codePoint) >= 0) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    public static void validateClaimKeys(List<String> claimKeys, List<String> allowedClaimKeys) {
        if (claimKeys == null || claimKeys.isEmpty()) {
            throw new CopilotSummaryValidationException("EMPTY_CLAIM_KEYS");
        }
        if (allowedClaimKeys == null || allowedClaimKeys.isEmpty()) {
            throw new CopilotSummaryValidationException("UNKNOWN_CLAIM_KEY");
        }
        for (String key : claimKeys) {
            if (key == null || key.isBlank() || !allowedClaimKeys.contains(key)) {
                throw new CopilotSummaryValidationException("UNKNOWN_CLAIM_KEY");
            }
        }
    }
}
