package com.ses.service.ai.copilot.scope;

/** 解決済みデータスコープ。ID集合そのものは保持せず、hashのみをrun metadataへ渡す。 */
public record CopilotScopeContext(
        String scopeType,
        String policyVersion,
        String scopeHash,
        boolean emptyPopulation,
        String tenantId,
        Long legalEntityId,
        String canonicalMembers
) {
    public CopilotScopeContext(String scopeType, String policyVersion, String scopeHash, boolean emptyPopulation) {
        this(scopeType, policyVersion, scopeHash, emptyPopulation, "legacy", 0L, "legacy");
    }
}
