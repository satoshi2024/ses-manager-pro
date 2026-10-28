package com.ses.privacy.foundation;

import java.security.PublicKey;
import java.util.Map;
import java.util.Objects;

/** verifierのpublic trust anchorと明示的なkey状態。 */
final class WatermarkKeyHistory {
    static final String SYNTHETIC_AUDIENCE = "TEST/SYNTHETIC";
    enum State { TRUSTED, COMPROMISED }
    record Entry(PublicKey publicKey, State state, String audience) {
        Entry {
            Objects.requireNonNull(publicKey, "publicKey");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(audience, "audience");
        }
    }
    private final Map<String, Entry> entries;
    WatermarkKeyHistory(Map<String, Entry> entries) { this.entries = Map.copyOf(entries); }
    Entry find(String keyId) { return entries.get(keyId); }
}
