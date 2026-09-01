package com.ses.service.ai.copilot.result;

import java.time.Instant;

public record CopilotFreshnessInfo(Instant sourceUpdatedAt, boolean stale, MetricBasis basis) {
}
