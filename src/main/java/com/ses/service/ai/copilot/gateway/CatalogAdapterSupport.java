package com.ses.service.ai.copilot.gateway;

import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.result.CopilotFreshnessInfo;
import com.ses.service.ai.copilot.result.CopilotLimitInfo;
import com.ses.service.ai.copilot.result.CopilotScopeInfo;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;

import java.util.List;

abstract class CatalogAdapterSupport {

    static final String DATA_VERSION = "1";

    protected TypedResultEnvelope envelope(
            SemanticCatalogEntry entry,
            CopilotScopeContext scope,
            CopilotExecutionContext context,
            List<com.ses.service.ai.copilot.result.MetricValue> values,
            List<com.ses.service.ai.copilot.result.BoundedResultRow> rows,
            MetricBasis basis,
            boolean truncated,
            int maxRows) {
        return new TypedResultEnvelope(
                entry.queryId(),
                entry.catalogVersion(),
                entry.resultSchemaVersion(),
                context.instant(),
                context.instant(),
                context.zoneId().getId(),
                new CopilotScopeInfo(scope.scopeType(), scope.policyVersion(), scope.scopeHash()),
                values,
                rows == null ? List.of() : rows,
                new CopilotFreshnessInfo(context.instant(), false, basis),
                entry.citationKeys(),
                new CopilotLimitInfo(maxRows, truncated),
                DATA_VERSION);
    }
}
