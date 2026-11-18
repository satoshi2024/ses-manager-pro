package com.ses.service.ai.copilot.summary;

/** Provider費用を推定で0円へ丸めないための固定状態。 */
public enum CopilotCostState {
    NOT_SENT,
    ACTUAL,
    UPPER_BOUND,
    UNKNOWN
}
