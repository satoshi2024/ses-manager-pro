package com.ses.common.exception;

import lombok.Getter;

/** PWA再送のpayload不一致・処理中・stale versionを差分付き409で返す。 */
@Getter
public class PwaConflictException extends RuntimeException {
    private final Object data;

    public PwaConflictException(String message, Object data) {
        super(message);
        this.data = data;
    }
}
