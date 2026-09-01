package com.ses.dto.ai;

import java.util.List;

/** B1: summary provider結果（数値は含めない）。 */
public record CopilotSummaryView(
        String text,
        List<String> claimKeys,
        String providerStatus,
        String modelVersion,
        boolean available
) {
    public static CopilotSummaryView unavailable(String status) {
        return new CopilotSummaryView(null, List.of(), status, null, false);
    }
}
