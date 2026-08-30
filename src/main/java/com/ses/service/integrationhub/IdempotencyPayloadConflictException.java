package com.ses.service.integrationhub;

/** 同一client/route/keyでdigestが異なる場合の安全な競合。入力値をmessageへ含めない。 */
public class IdempotencyPayloadConflictException extends RuntimeException {
    public IdempotencyPayloadConflictException() {
        super("idempotency key is already bound to another request digest");
    }
}
