package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import org.springframework.stereotype.Component;

/**
 * legacy AI resource requestのscope snapshotをExecutionContextへ一度だけ束縛する。
 * ID集合の再計算は行わず、factoryが束縛したsnapshotだけを利用する。
 */
@Component
public class LegacyAiExecutionContextBinder {
    private static final String QUERY_ID = "legacy.ai";

    public CopilotExecutionContext bind(CopilotExecutionContext context) {
        if (context == null || context.effectiveScopeSnapshot() == null) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
        EffectiveScopeSnapshot snapshot = context.effectiveScopeSnapshot();
        if (context.queryId() != null || context.parameters() != null
                || (context.scope() != null && context.scope() != snapshot.scope())) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_ALREADY_BOUND");
        }
        context.bind(QUERY_ID, CopilotQueryParameters.ofQuery(QUERY_ID), snapshot.scope());
        return context;
    }
}
