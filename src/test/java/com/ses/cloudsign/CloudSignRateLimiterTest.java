package com.ses.cloudsign;

import com.ses.config.CloudSignProperties;
import com.ses.service.cloudsign.CloudSignRateLimiter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HFP-02-AC-09-02: 共通rate limiterのbudget制御。
 * 公式上限800を超えない設定で、budget枯渇時は待機して429自滅しない。
 */
class CloudSignRateLimiterTest {

    private CloudSignRateLimiter limiter(int budget) {
        CloudSignProperties p = new CloudSignProperties();
        p.setRateLimitPerMinute(budget);
        return new CloudSignRateLimiter(p);
    }

    private CloudSignRateLimiter fastLimiter(int budget, long windowMillis) {
        CloudSignProperties p = new CloudSignProperties();
        p.setRateLimitPerMinute(budget);
        return new CloudSignRateLimiter(p, windowMillis);
    }

    @Test
    void budgetは公式上限800を超えない() {
        CloudSignProperties p = new CloudSignProperties();
        p.setRateLimitPerMinute(5000);
        CloudSignRateLimiter limiter = new CloudSignRateLimiter(p);
        assertTrue(limiter.maxPerMinute() <= 800, "公式上限800を超えない");
    }

    @Test
    void budget内は即時許可される() {
        CloudSignRateLimiter limiter = limiter(100);
        for (int i = 0; i < 50; i++) {
            limiter.acquire();
        }
        assertEquals(50, limiter.consumedInWindow());
    }

    @Test
    void budget超過時は待機してから許可される() throws Exception {
        CloudSignRateLimiter limiter = fastLimiter(2, 100);
        limiter.acquire();
        limiter.acquire();
        long start = System.currentTimeMillis();
        limiter.acquire();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 90, "windowが空くまで待機する（観測: " + elapsed + "ms）");
        assertTrue(limiter.consumedInWindow() <= 2, "待機後もbudget内を維持");
    }

    @Test
    void 複数スレッドでもbudgetを超えて消費しない() throws Exception {
        CloudSignRateLimiter limiter = fastLimiter(10, 50);
        AtomicInteger passed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        int threads = 8;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < 10; j++) {
                    limiter.acquire();
                    passed.incrementAndGet();
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join(30_000);
        }
        // 80要求が全件処理され、同一window内の同時消費がbudget(10)を超えない
        assertEquals(80, passed.get(), "全要求が最終的に許可される");
        assertTrue(limiter.consumedInWindow() <= 10, "window内の同時消費はbudget以下");
    }
}
