package com.ses.service.ai.copilot.result;

import java.time.Instant;
import java.util.List;

/** 正本service結果のtyped envelope。数値の正本は values のみ。 */
public record TypedResultEnvelope(
        String queryId,
        String catalogVersion,
        String resultSchemaVersion,
        Instant asOf,
        Instant generatedAt,
        String tenantTimezone,
        CopilotScopeInfo scope,
        List<MetricValue> values,
        List<BoundedResultRow> rows,
        CopilotFreshnessInfo freshness,
        List<String> citationKeys,
        CopilotLimitInfo limit,
        String dataVersion
) {
}
