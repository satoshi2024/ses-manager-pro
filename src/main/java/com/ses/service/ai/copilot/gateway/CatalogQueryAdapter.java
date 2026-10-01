package com.ses.service.ai.copilot.gateway;

import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.common.exception.BusinessException;

public interface CatalogQueryAdapter {

    String queryId();

    default TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters,
                                        CopilotScopeContext scope) {
        throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    /** canonical pipeline用。各adapterは同じcontextを正本serviceへ渡す。 */
    default TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters,
                                        CopilotScopeContext scope, CopilotExecutionContext context) {
        if (context == null) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
        throw BusinessException.of(501, "CONTEXT_ADAPTER_NOT_IMPLEMENTED");
    }
}
