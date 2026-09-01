package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.AiGatewayRequest;

import java.util.List;
import java.util.regex.Pattern;

/** summary text / claimKeys の契約検証。 */
public final class CopilotSummaryValidator {

    private static final Pattern HTML = Pattern.compile("(?i)<[^>]+>");
    private static final Pattern NUMERIC_RECALC = Pattern.compile("[¥￥]?\\s?[0-9]{2,}");

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
        if (NUMERIC_RECALC.matcher(summaryText).find()) {
            throw new CopilotSummaryValidationException("NUMERIC_RECALC_REJECTED");
        }
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
