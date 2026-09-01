package com.ses.service.ai.copilot.scope;

/** 解決済みデータスコープ。ID集合そのものは保持せず、hashのみをrun metadataへ渡す。 */
public record CopilotScopeContext(
        String scopeType,
        String policyVersion,
        String scopeHash,
        boolean emptyPopulation
) {
}
