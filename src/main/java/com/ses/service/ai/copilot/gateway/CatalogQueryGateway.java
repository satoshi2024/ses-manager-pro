package com.ses.service.ai.copilot.gateway;

import com.ses.common.exception.BusinessException;
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
        CatalogQueryAdapter adapter = adapters.get(entry.queryId());
        if (adapter == null) {
            throw BusinessException.of(404, "CATALOG_NOT_FOUND");
        }
        return adapter.execute(entry, parameters, scope);
    }
}
