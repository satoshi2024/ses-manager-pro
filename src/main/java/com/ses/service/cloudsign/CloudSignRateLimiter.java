package com.ses.service.cloudsign;

import com.ses.config.CloudSignProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 同一access tokenの毎分request上限を守る共通limiter（HFP-02-AC-09-02）。
 * 公式上限800 request/token/minを超えないbudget（既定500）で、poll/dispatch/manual sync/token再試行を
 * 一つのbudgetとして扱う。budget枯渇時はretry-after相当の待機でブロックする（429自滅を防ぐ）。
 */
@Slf4j
@Component
public class CloudSignRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int maxPerMinute;
    private final long windowMillis;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CloudSignRateLimiter(CloudSignProperties properties) {
        this(properties, WINDOW_MILLIS);
    }

    /** テスト用: window幅を短縮してbudget制御を高速に検証する。 */
    public CloudSignRateLimiter(CloudSignProperties properties, long windowMillis) {
        int configured = properties.getRateLimitPerMinute();
        this.maxPerMinute = Math.max(1, Math.min(800, configured));
        this.windowMillis = Math.max(1, windowMillis);
    }

    /**
     * 1 request分のbudgetを消費する。枯渇していればwindowが空くまで待機する。
     * 待機中に割り込まれた場合はInterruptedExceptionをRuntimeExceptionで包む（処理は安全側に中断）。
     */
    public void acquire() {
        long waitMillis;
        synchronized (this) {
            long now = System.currentTimeMillis();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxPerMinute) {
                long oldest = timestamps.peekFirst();
                waitMillis = windowMillis - (now - oldest);
            } else {
                waitMillis = 0;
            }
            if (waitMillis > 0) {
                log.warn("[CloudSign] rate limit budget枯渇: 約{}ms待機します", waitMillis);
            }
            if (waitMillis <= 0) {
                timestamps.addLast(now);
            }
        }
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("rate limit待機が中断されました", e);
            }
            acquire();
        }
    }

    /** テスト用: 現在のbudget消費状態を返す。 */
    public synchronized int consumedInWindow() {
        long now = System.currentTimeMillis();
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= WINDOW_MILLIS) {
            timestamps.pollFirst();
        }
        return timestamps.size();
    }

    /** テスト用: 設定budgetを返す。 */
    public int maxPerMinute() {
        return maxPerMinute;
    }
}
