package com.ses.service.ai.copilot.summary;

import java.util.List;

/** provider応答。UI数値はtyped resultが正本。 */
public record SummaryResponse(
        String summaryText,
        List<String> claimKeys,
        String providerStatus,
        String modelVersion,
        long latencyMs,
        Integer tokenCount,
        Integer costYen,
        SummaryCostEvidence costEvidence
) {
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATUS_REJECTED = "REJECTED";

    public SummaryResponse(String summaryText, List<String> claimKeys, String providerStatus,
                           String modelVersion, long latencyMs, Integer tokenCount, Integer costYen) {
        this(summaryText, claimKeys, providerStatus, modelVersion, latencyMs, tokenCount, costYen,
                SummaryCostEvidence.unknown(providerStatus));
    }

    public boolean isAvailable() {
        return STATUS_SUCCEEDED.equals(providerStatus)
                && summaryText != null
                && !summaryText.isBlank();
    }

    public static SummaryResponse unavailable(String reason) {
        return unavailable(reason, SummaryCostEvidence.unknown(reason));
    }

    public static SummaryResponse unavailable(String reason, SummaryCostEvidence evidence) {
        return new SummaryResponse(null, List.of(), reason, null, 0L, null, null,
                evidence == null ? SummaryCostEvidence.unknown(reason) : evidence);
    }
}
