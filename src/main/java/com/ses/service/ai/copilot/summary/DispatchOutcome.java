package com.ses.service.ai.copilot.summary;

/** immutableなdispatch境界の観測状態。reasonCodeは固定コードのみを保持する。 */
public record DispatchOutcome(State state, String reasonCode) {
    public enum State {
        NOT_STARTED,
        MAY_HAVE_BEEN_SENT,
        RESPONSE_RECEIVED
    }

    public DispatchOutcome {
        if (state == null) {
            throw new IllegalArgumentException("dispatch state is required");
        }
        reasonCode = reasonCode == null ? "" : reasonCode;
    }

    public static DispatchOutcome notStarted(String reasonCode) {
        return new DispatchOutcome(State.NOT_STARTED, reasonCode);
    }
}
