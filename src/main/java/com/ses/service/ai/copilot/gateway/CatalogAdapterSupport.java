package com.ses.service.ai.copilot.gateway;

import com.ses.service.ai.copilot.result.CopilotFreshnessInfo;
import com.ses.service.ai.copilot.result.CopilotLimitInfo;
import com.ses.service.ai.copilot.result.CopilotScopeInfo;
import com.ses.service.ai.copilot.result.MetricBasis;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.common.exception.BusinessException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

abstract class CatalogAdapterSupport {

    static final String DATA_VERSION = "1";

    protected void requireContext(CopilotExecutionContext context) {
        if (context == null || context.parameters() == null || context.scope() == null
                || context.effectiveScopeSnapshot() == null
                || context.scope() != context.effectiveScopeSnapshot().scope()
                || context.scopeHash() == null
                || !context.scopeHash().equals(context.effectiveScopeSnapshot().scope().scopeHash())) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
    }

    protected TypedResultEnvelope envelope(
            SemanticCatalogEntry entry,
            CopilotScopeContext scope,
            List<com.ses.service.ai.copilot.result.MetricValue> values,
            List<com.ses.service.ai.copilot.result.BoundedResultRow> rows,
            MetricBasis basis,
            boolean truncated,
            int maxRows,
            CopilotExecutionContext context) {
        if (context == null || scope == null || context.effectiveScopeSnapshot() == null
                || context.scope() != scope || !context.tenantId().equals(scope.tenantId())
                || !context.legalEntityId().equals(scope.legalEntityId())
                || !context.scopeHash().equals(scope.scopeHash())) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
        Instant now = context.asOf();
        return new TypedResultEnvelope(
                entry.queryId(),
                entry.catalogVersion(),
                entry.resultSchemaVersion(),
                now,
                now,
                context.zoneId().getId(),
                new CopilotScopeInfo(scope.scopeType(), scope.policyVersion(), scope.scopeHash()),
                values,
                rows == null ? List.of() : rows,
                new CopilotFreshnessInfo(now, false, basis),
                entry.citationKeys(),
                new CopilotLimitInfo(maxRows, truncated),
                DATA_VERSION);
    }
}
