package com.ses.service.ai.copilot.summary;

import java.time.Duration;
import java.util.List;

/** management copilot summary provider への入力（数値・raw prompt・scope外IDは含めない）。 */
public record SummaryRequest(
        String queryId,
        String catalogVersion,
        String promptVersion,
        String outputSchemaVersion,
        List<String> allowedClaimKeys,
        Duration deadline,
        int costBudgetYen
) {
}
