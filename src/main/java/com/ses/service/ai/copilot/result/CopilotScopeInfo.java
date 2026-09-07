package com.ses.service.ai.copilot.result;

/** scopeメタデータ。ID集合は含めない。 */
public record CopilotScopeInfo(String type, String policyVersion, String hash) {
}
