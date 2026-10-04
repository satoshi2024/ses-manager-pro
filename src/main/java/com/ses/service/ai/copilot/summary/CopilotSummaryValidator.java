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
    private static final String KANJI_NUMERAL_CHARS = "〇零一二三四五六七八九十百千万億兆京垓"
            + "壱弐参肆伍陸漆捌玖拾佰阡仟萬";
    private static final String KANJI_NUMERAL_CLASS = "[" + KANJI_NUMERAL_CHARS + "]";
    private static final Pattern KANJI_NUMERIC_EXPRESSION = Pattern.compile(
            KANJI_NUMERAL_CLASS + "{2,}"
                    + "|" + KANJI_NUMERAL_CLASS + "+\\s*(?:円|ドル|ユーロ|ウォン|人|名|件|社|回|月|年|日|週|"
                    + "週間|時間|分|秒|個|本|冊|台|席|倍|割|ポイント|パーセント|%)"
                    + "|(?:数値|売上|利益|粗利|人数|件数|稼働率|割合|差分|増減|予算|金額|単価|合計|平均)"
                    + "(?:は|が|:|：)?\\s*" + KANJI_NUMERAL_CLASS + "+");
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
        if (KANJI_NUMERIC_EXPRESSION.matcher(summaryText).find()) {
            return true;
        }
        for (int offset = 0; offset < summaryText.length();) {
            int codePoint = summaryText.codePointAt(offset);
            int category = Character.getType(codePoint);
            if (NUMBER_TYPES.contains(category)
                    || category == Character.CURRENCY_SYMBOL
                    || PERCENT_SYMBOLS.contains(codePoint)) {
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
