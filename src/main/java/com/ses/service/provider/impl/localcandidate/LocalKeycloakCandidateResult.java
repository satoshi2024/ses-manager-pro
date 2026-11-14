package com.ses.service.provider.impl.localcandidate;

import java.time.Duration;
import java.util.Objects;

/** 操作ごとの期限付きACK/観測結果。全範囲の失効成功状態を持たない。 */
record LocalKeycloakCandidateResult(Operation operation, State state, String safeCode,
                                   Integer httpStatus, Boolean enabled, Integer activeSessionCount,
                                   int responseBytes, Duration elapsed) {
    enum Operation { DISABLE, LOGOUT, READ_USER, READ_SESSIONS }
    enum State { NOT_STARTED, ACKNOWLEDGED, MAY_HAVE_BEEN_SENT, UNKNOWN }

    LocalKeycloakCandidateResult {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(state);
        Objects.requireNonNull(safeCode);
        Objects.requireNonNull(elapsed);
        if (responseBytes < 0) throw new IllegalArgumentException("negative response size");
    }

    @Override
    public String toString() {
        return "LocalKeycloakCandidateResult[operation=" + operation + ", state=" + state
                + ", safeCode=" + safeCode + ", httpStatus=" + httpStatus
                + ", responseBytes=" + responseBytes + ", elapsed=" + elapsed + "]";
    }
}
