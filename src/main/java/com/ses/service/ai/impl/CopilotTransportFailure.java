package com.ses.service.ai.impl;

import com.ses.service.ai.copilot.summary.DispatchOutcome;
import com.ses.common.exception.BusinessException;

/** transport実行境界の観測状態を失敗時も保持する。 */
final class CopilotTransportFailure extends BusinessException {
    private final String reasonCode;
    private final DispatchOutcome outcome;

    CopilotTransportFailure(String reasonCode, DispatchOutcome.State state) {
        super(status(reasonCode), reasonCode);
        this.reasonCode = reasonCode;
        this.outcome = new DispatchOutcome(state, reasonCode);
    }

    String reasonCode() { return reasonCode; }
    DispatchOutcome outcome() { return outcome; }

    private static int status(String code) {
        return "COPILOT_DEADLINE_EXCEEDED".equals(code) ? 504
                : "COPILOT_RESPONSE_TOO_LARGE".equals(code) ? 502 : 503;
    }
}
