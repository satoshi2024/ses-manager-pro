package com.ses.service.ai.copilot.gateway;

import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;

public interface CatalogQueryAdapter {

    String queryId();

    TypedResultEnvelope execute(SemanticCatalogEntry entry, CopilotQueryParameters parameters, CopilotScopeContext scope);
}
