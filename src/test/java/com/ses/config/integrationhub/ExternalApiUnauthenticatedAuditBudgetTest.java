package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** N5-P2-1: 未認証rejectの監査行は1分あたり上限件数までで、分境界で枠が戻る。 */
class ExternalApiUnauthenticatedAuditBudgetTest {

    @Test
    void budgetIsConsumedWithinOneMinuteAndRefilledAtTheNextMinuteBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-14T00:00:00Z"));
        ExternalApiUnauthenticatedAuditBudget budget = budget(3, clock);

        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());
        clock.set(Instant.parse("2026-11-14T00:00:59.999Z"));
        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire(), "同じ分の4件目は永続化しない");

        clock.set(Instant.parse("2026-11-14T00:01:00Z"));
        assertTrue(budget.tryAcquire(), "次の分で枠が戻る");
    }

    @Test
    void zeroLimitAndMissingPropertiesNeverGrantPersistence() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-14T00:00:00Z"));
        assertFalse(budget(0, clock).tryAcquire());

        ExternalApiUnauthenticatedAuditBudget missing = new ExternalApiUnauthenticatedAuditBudget(
                providerOf(null), providerOf(clock));
        assertFalse(missing.tryAcquire());
    }

    private static ExternalApiUnauthenticatedAuditBudget budget(int limit, Clock clock) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setUnauthenticatedAuditMaxPerMinute(limit);
        return new ExternalApiUnauthenticatedAuditBudget(providerOf(properties), providerOf(clock));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
