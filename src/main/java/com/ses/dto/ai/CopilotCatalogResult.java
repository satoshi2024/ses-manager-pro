package com.ses.dto.ai;

import java.util.List;

/** F1: catalog解決とrun記録の公開応答。raw prompt・業務数値は含めない。 */
public record CopilotCatalogResult(
        String queryId,
        String catalogVersion,
        String resultSchemaVersion,
        String status,
        String message,
        String traceId,
        Long runId,
        List<String> citationKeys
) {
}
