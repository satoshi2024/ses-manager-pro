package com.ses.dto.pwa;

import java.time.Instant;

public record PwaRestorePermitResponse(String documentNonce, String userScope,
                                      Instant serverTime, Instant permitExpiresAt) {
    @Override
    public String toString() {
        return "PwaRestorePermitResponse[documentNonce=REDACTED, userScope=REDACTED, serverTime="
                + serverTime + ", permitExpiresAt=" + permitExpiresAt + "]";
    }
}
