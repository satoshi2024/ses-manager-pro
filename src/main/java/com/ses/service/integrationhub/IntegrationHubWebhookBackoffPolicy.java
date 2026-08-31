package com.ses.service.integrationhub;

import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;

/** timeout/429/5xxのbounded exponential backoff+jitter。最大attemptは8で固定する。 */
public final class IntegrationHubWebhookBackoffPolicy {
    public static final int MAX_ATTEMPTS = 8;
    private static final long BASE_SECONDS = 5L;
    private static final long MAX_SECONDS = 3600L;

    private final IntSupplier jitterSeconds;

    public IntegrationHubWebhookBackoffPolicy() {
        this(() -> java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 5));
    }

    public IntegrationHubWebhookBackoffPolicy(IntSupplier jitterSeconds) {
        this.jitterSeconds = Objects.requireNonNull(jitterSeconds);
    }

    public Duration delayForAttempt(int attemptCount) {
        if (attemptCount < 1 || attemptCount > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("invalid webhook attempt count");
        }
        long exponential = BASE_SECONDS * (1L << Math.min(attemptCount - 1, 10));
        long bounded = Math.min(MAX_SECONDS, exponential);
        int jitter = jitterSeconds.getAsInt();
        if (jitter < 0 || jitter > 4) {
            throw new IllegalStateException("webhook jitter is outside bounded range");
        }
        return Duration.ofSeconds(Math.min(MAX_SECONDS, bounded + jitter));
    }
}
