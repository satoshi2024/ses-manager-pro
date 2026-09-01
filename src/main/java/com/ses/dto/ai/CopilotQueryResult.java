package com.ses.dto.ai;

import com.ses.service.ai.copilot.result.TypedResultEnvelope;

import java.util.List;

/** F2/A1: catalog解決・typed result・citation再認可の公開応答。raw promptは含めない。 */
public record CopilotQueryResult(
        String queryId,
        String catalogVersion,
        String resultSchemaVersion,
        String status,
        String message,
        String traceId,
        Long runId,
        List<String> citationKeys,
        List<ResolvedCitationDto> citations,
        TypedResultEnvelope result
) {
}
