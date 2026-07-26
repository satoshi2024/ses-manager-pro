package com.ses.service.export;

import com.ses.common.exception.BusinessException;

import java.util.concurrent.Semaphore;

/** 同一プロセス内の一覧エクスポート同時実行数を制限する。 */
public final class ExportConcurrencyLimiter {

    private static volatile Semaphore slots = new Semaphore(2);

    private ExportConcurrencyLimiter() {
    }

    public static synchronized void configure(int permits) {
        slots = new Semaphore(permits > 0 ? permits : 2);
    }

    public static void acquire() {
        if (!slots.tryAcquire()) {
            throw BusinessException.of(429, "error.export.concurrent");
        }
    }

    public static void release() {
        slots.release();
    }
}
