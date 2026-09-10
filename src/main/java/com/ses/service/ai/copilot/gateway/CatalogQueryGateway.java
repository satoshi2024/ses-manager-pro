package com.ses.service.ai.copilot.gateway;

import com.ses.common.exception.BusinessException;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * catalog IDに応じて正本service adapterへ委譲する。LLM・AiTextServiceは使用しない。
 */
@Service
public class CatalogQueryGateway {

    private final Map<String, CatalogQueryAdapter> adapters;

    public CatalogQueryGateway(List<CatalogQueryAdapter> adapterList) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toUnmodifiableMap(CatalogQueryAdapter::queryId, Function.identity()));
    }

    public TypedResultEnvelope execute(
            SemanticCatalogEntry entry,
            CopilotQueryParameters parameters,
            CopilotScopeContext scope) {
        throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    public TypedResultEnvelope execute(SemanticCatalogEntry entry,
                                       CopilotQueryParameters parameters,
                                       CopilotScopeContext scope,
                                       CopilotExecutionContext context) {
        if (entry == null || !entry.enabled()) {
            throw BusinessException.of(403, "CATALOG_DISABLED");
        }
        if (entry.queryId() == null || parameters == null || !entry.queryId().equals(parameters.queryId())
                || context == null || context.effectiveScopeSnapshot() == null
                || context.scope() == null || context.scope() != scope
                || context.scope() != context.effectiveScopeSnapshot().scope()
                || context.parameters() != parameters
                || context.queryId() == null || !entry.queryId().equals(context.queryId())
                || !context.tenantId().equals(scope.tenantId())
                || !context.legalEntityId().equals(scope.legalEntityId())
                || !context.asOfDate().equals(context.effectiveScopeSnapshot().asOf())
                || context.scopeHash() == null || scope.scopeHash() == null
                || !context.scopeHash().equals(context.effectiveScopeSnapshot().scopeHash())
                || !context.scopeHash().equals(scope.scopeHash())) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
        CatalogQueryAdapter adapter = adapters.get(entry.queryId());
        if (adapter == null) {
            throw BusinessException.of(404, "CATALOG_NOT_FOUND");
        }
        return adapter.execute(entry, parameters, scope, context);
    }
}
