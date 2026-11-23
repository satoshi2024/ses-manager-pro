package com.ses.featureenablement;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.state.EnablementState;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 試験の snapshot を組み立てる補助（DB を読まない unit test 用）。 */
public final class FeatureEnablementTestSnapshots {

    private FeatureEnablementTestSnapshots() {
    }

    public static Builder builder(Instant now) {
        return new Builder(now);
    }

    /** snapshot の組立て。 */
    public static final class Builder {

        private final Instant now;
        private long generation = 1L;
        private Instant verifiedAt;
        private boolean masterAllowed = true;
        private final Map<Feature, FeatureEnablementSnapshot.UnitView> units = new EnumMap<>(Feature.class);
        private final Map<String, String> settings = new HashMap<>();
        private final Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs = new LinkedHashMap<>();
        private final Map<String, FeatureEnablementSnapshot.BundleView> bundles = new LinkedHashMap<>();

        private Builder(Instant now) {
            this.now = now;
            this.verifiedAt = now;
        }

        public Builder generation(long value) {
            this.generation = value;
            return this;
        }

        public Builder verifiedAt(Instant value) {
            this.verifiedAt = value;
            return this;
        }

        public Builder master(boolean allowed) {
            this.masterAllowed = allowed;
            return this;
        }

        /** 開いている開放単位（要る署名の期限は無期限）。 */
        public Builder open(Feature feature) {
            return unit(feature, EnablementState.OPEN, LocalDate.MAX, null);
        }

        public Builder unit(Feature feature, EnablementState state, LocalDate openUntil, Instant unverifiableSince) {
            units.put(feature, new FeatureEnablementSnapshot.UnitView(state, 0, openUntil, unverifiableSince, null));
            return this;
        }

        public Builder setting(String key, String value) {
            settings.put(key, value);
            return this;
        }

        public Builder signOff(String kind, long id, int version, LocalDate signedOn, LocalDate expiresOn) {
            signOffs.put(kind, new FeatureEnablementSnapshot.ConfirmedSignOff(id, version, kind, "REF-" + id, "SRE",
                    signedOn, expiresOn, null, "EVIDENCE"));
            return this;
        }

        /** 確認済みの組（member は種別 → 署名 ID）。 */
        public Builder bundle(String bundleKind, long id, int version, Map<String, Long> members) {
            Map<String, FeatureEnablementSnapshot.BundleMemberView> views = new LinkedHashMap<>();
            members.forEach((kind, signOffId) -> views.put(kind, new FeatureEnablementSnapshot.BundleMemberView(signOffId,
                    kind, "REF-" + signOffId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, "CONFIRMED")));
            bundles.put(bundleKind, new FeatureEnablementSnapshot.BundleView(id, version, bundleKind, views));
            return this;
        }

        public FeatureEnablementSnapshot build() {
            return new FeatureEnablementSnapshot(generation, now, verifiedAt, masterAllowed, units, settings, signOffs,
                    bundles);
        }
    }
}
