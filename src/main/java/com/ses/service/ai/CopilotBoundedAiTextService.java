package com.ses.service.ai;

/** MANAGEMENT_COPILOT専用のdeadline-awareなadapter契約。legacy generate(String)へ委譲しない。 */
public interface CopilotBoundedAiTextService extends AiTextService {

    /** MANAGEMENT_COPILOT artifactに登録する固定model identity。 */
    String copilotModelId();

    /** 実adapterと実prompt templateから計算したartifact identity。 */
    String copilotPromptVersion();

    String copilotConfigHash();

    /** 指定deadline内に応答し、時間切れ時に実行中I/Oを停止する。 */
    String generateCopilotSummary(String prompt, long deadlineNanos);
}
