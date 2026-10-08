package com.ses.service.ai;

import com.ses.service.ai.copilot.CopilotInvocation;

/** MANAGEMENT_COPILOT専用のdeadline-awareなadapter契約。remote adapterはlegacy generate(String)へ委譲しない。 */
public interface CopilotBoundedAiTextService extends AiTextService {

    /** MANAGEMENT_COPILOT artifactに登録する固定model identity。 */
    String copilotModelId();

    /** 実adapterと実prompt templateから計算したartifact identity。 */
    String copilotPromptVersion();

    String copilotConfigHash();

    /** 指定deadline内に応答し、時間切れ時に実行中I/Oを停止する。 */
    String generateCopilotSummary(String prompt, long deadlineNanos);

    /** immutable invocationを受けるCopilot専用境界。local providerは既存deadline実装を再利用する。 */
    default String generateCopilotSummary(String prompt, CopilotInvocation invocation) {
        if (invocation == null || !invocation.identityMatchesSnapshot()) {
            throw com.ses.common.exception.BusinessException.of(503, "COPILOT_EXECUTION_SNAPSHOT_INVALID");
        }
        if (!invocation.preflightCostEvidence().dispatchAllowed()) {
            throw com.ses.common.exception.BusinessException.of(503,
                    invocation.preflightCostEvidence().reasonCode());
        }
        return generateCopilotSummary(prompt, invocation.deadlineNanos());
    }
}
