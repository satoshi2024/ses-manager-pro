package com.ses.service.ai.copilot;

/**
 * 経営コパイロット回答へのfeedback種別。推薦の ACCEPT/REJECT/HOLD とは別型。
 */
public enum CopilotFeedbackType {
    HELPFUL,
    INCORRECT,
    UNSAFE
}
