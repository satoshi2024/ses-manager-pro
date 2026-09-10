package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AiFeedback;
import com.ses.service.ai.copilot.CopilotExecutionContext;

public interface AiFeedbackService {

    /**
     * legacy推薦feedbackは、resource scope contractが完成するまでdisabledとする。
     * bare itemId/runIdだけの認可経路を残さないため、呼出しには元queryのcontextを要求する。
     */
    AiFeedback record(FeedbackCommand command, CopilotExecutionContext context);

    /** 旧signatureはsource compatibilityのため残すが、認可・保存には絶対に使わない。 */
    @Deprecated
    default AiFeedback record(Long itemId, String decision, String reasonCode, String comment) {
        throw BusinessException.of(403, "SCOPE_CONTEXT_REQUIRED");
    }

    record FeedbackCommand(Long itemId, Long runId, String decision,
                           String reasonCode, String comment) {
    }
}
