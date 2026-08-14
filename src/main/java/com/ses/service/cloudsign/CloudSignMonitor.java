package com.ses.service.cloudsign;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CloudSign連携の低cardinality metricsとalert判定（HFP-02-AC-06-05 / AC-09-04）。
 * document/operation/email/tokenをtagにしない。値はJVMメモリのみ（履歴保持はHFP-02-10のmonitoring連携）。
 */
@Slf4j
@Component
public class CloudSignMonitor {

    private final AtomicLong pollRuns = new AtomicLong();
    private final AtomicLong pollFailures = new AtomicLong();
    private final AtomicLong lastPollSuccessAt = new AtomicLong();
    private final AtomicLong lastPollDurationMs = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong serverErrors = new AtomicLong();
    private final AtomicLong tokenFailures = new AtomicLong();
    private final AtomicLong unknownStatus = new AtomicLong();
    private final AtomicLong resultUnknown = new AtomicLong();
    private final AtomicLong staleQueue = new AtomicLong();
    private final AtomicLong pollingStops = new AtomicLong();

    public void recordPollStart() {
        pollRuns.incrementAndGet();
    }

    public void recordPollSuccess(long durationMs) {
        lastPollSuccessAt.set(System.currentTimeMillis());
        lastPollDurationMs.set(durationMs);
    }

    public void recordPollFailure() {
        pollFailures.incrementAndGet();
    }

    public void recordError(com.ses.common.enums.CloudSignErrorCode code) {
        switch (code) {
            case RATE_LIMITED -> rateLimited.incrementAndGet();
            case SERVER_ERROR, TIMEOUT, NETWORK -> serverErrors.incrementAndGet();
            case UNAUTHORIZED, INVALID_CLIENT -> tokenFailures.incrementAndGet();
            default -> {
            }
        }
    }

    public void recordUnknownStatus() {
        unknownStatus.incrementAndGet();
    }

    public void recordResultUnknown() {
        resultUnknown.incrementAndGet();
    }

    public void recordStaleQueue() {
        staleQueue.incrementAndGet();
    }

    public void recordPollingStop() {
        pollingStops.incrementAndGet();
    }

    /** pollが長時間成功していない（停止疑い）ときにalert判定する。 */
    public boolean isPollingStale(Duration maxIdle) {
        long last = lastPollSuccessAt.get();
        if (last == 0) {
            return true; // 起動後一度も成功していない
        }
        return System.currentTimeMillis() - last > maxIdle.toMillis();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pollRuns", pollRuns.get());
        m.put("pollFailures", pollFailures.get());
        m.put("lastPollSuccessAt", lastPollSuccessAt.get() == 0 ? null
                : Instant.ofEpochMilli(lastPollSuccessAt.get()).toString());
        m.put("lastPollDurationMs", lastPollDurationMs.get());
        m.put("rateLimited", rateLimited.get());
        m.put("serverErrors", serverErrors.get());
        m.put("tokenFailures", tokenFailures.get());
        m.put("unknownStatus", unknownStatus.get());
        m.put("resultUnknown", resultUnknown.get());
        m.put("staleQueue", staleQueue.get());
        m.put("pollingStops", pollingStops.get());
        return m;
    }
}
