package com.ses.service.ai.copilot.result;

import java.util.List;
import java.util.Map;

/** 行単位のbounded結果。PII列は含めず、claim key用のredacted summaryのみ。 */
public record BoundedResultRow(String rowKey, Map<String, String> redactedFields) {
}
