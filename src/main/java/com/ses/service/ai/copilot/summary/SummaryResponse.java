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
        Integer costYen
) {
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATUS_REJECTED = "REJECTED";

    public boolean isAvailable() {
        return STATUS_SUCCEEDED.equals(providerStatus)
                && summaryText != null
                && !summaryText.isBlank();
    }

    public static SummaryResponse unavailable(String reason) {
        return new SummaryResponse(null, List.of(), reason, null, 0L, null, null);
    }
}
