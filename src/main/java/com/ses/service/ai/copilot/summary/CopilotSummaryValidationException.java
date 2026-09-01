package com.ses.service.ai.copilot.summary;

/** summary provider契約違反。metricsは維持しsummaryのみ不可用とする。 */
public class CopilotSummaryValidationException extends RuntimeException {

    private final String reasonCode;

    public CopilotSummaryValidationException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
