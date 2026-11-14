package com.ses.service.pwa;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** logout後の遅延callback/responseを拒否するsession失効世代。 */
@Component
public class PwaRestoreSessionRegistry {
    private static final long TOMBSTONE_SECONDS = 60 * 60;
    private final ConcurrentHashMap<String, Instant> destroyed = new ConcurrentHashMap<>();
    private final Clock clock;

    public PwaRestoreSessionRegistry(Clock clock) { this.clock = clock; }

    public void destroyed(String sessionId) {
        prune();
        destroyed.put(sessionId, clock.instant());
    }

    public boolean isDestroyed(String sessionId) {
        Instant at = destroyed.get(sessionId);
        if (at == null) return false;
        if (!at.plusSeconds(TOMBSTONE_SECONDS).isAfter(clock.instant())) {
            destroyed.remove(sessionId, at);
            return false;
        }
        return true;
    }

    private void prune() {
        Instant cutoff = clock.instant().minusSeconds(TOMBSTONE_SECONDS);
        destroyed.entrySet().removeIf(entry -> !entry.getValue().isAfter(cutoff));
    }
}
