package com.ses.dto.pwa;

import java.time.Instant;

public record PwaRestoreProofResponse(String proof, Instant expiresAt) {
    @Override
    public String toString() {
        return "PwaRestoreProofResponse[proof=REDACTED, expiresAt=" + expiresAt + "]";
    }
}
