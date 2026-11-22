package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * DB の源（prod の既定。design §3.1・§3.2）。snapshot（memory）だけを読み、DB に往復しない（照会 0 回。FE-AC-006-02）。
 *
 * <pre>
 * s = holder.get()
 * if now - s.verifiedAt &gt; maxStaleness: 閉（fail-closed）
 * if !s.masterAllowed: 閉
 * u = s.units[f]; if u.state != OPEN: 閉                    // OPEN_PENDING_RESTART は閉
 * if !(today(JST) &lt; u.openUntil): 閉                       // 要る署名の失効・欠落
 * if u.unverifiableSince != null: 閉                         // 評価できない（全 instance で共有）
 * if overlay.contains(f): 閉                                  // この instance の一時の閉鎖
 * 開
 * </pre>
 *
 * <p>段階的な合入の間は合成の源として動く: 目録の DB 管理の印が無い開放単位は property の値を返す（design §3.1）。go-live は段階 E
 * の後に限る（D-1）。</p>
 */
public class DatabaseFeatureEnablementStateSource implements FeatureEnablementStateSource {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final FeatureEnablementSnapshotHolder holder;
    private final FeatureEnablementOverlay overlay;
    private final FeatureEnablementCatalog catalog;
    private final FeatureEnablementStateSource fallback;
    private final FeatureEnablementProperties properties;
    private final Clock clock;

    public DatabaseFeatureEnablementStateSource(FeatureEnablementSnapshotHolder holder, FeatureEnablementOverlay overlay,
                                                FeatureEnablementCatalog catalog, FeatureEnablementStateSource fallback,
                                                FeatureEnablementProperties properties, Clock clock) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean isOpen(Feature feature) {
        if (feature == null) {
            return false;
        }
        if (!catalog.databaseManaged(feature)) {
            return fallback.isOpen(feature);
        }
        FeatureEnablementSnapshot snapshot = holder.get();
        if (stale(snapshot) || !snapshot.masterAllowed()) {
            return false;
        }
        FeatureEnablementSnapshot.UnitView unit = snapshot.unit(feature);
        if (unit.state() != EnablementState.OPEN) {
            return false;
        }
        if (!today().isBefore(unit.openUntil())) {
            return false;
        }
        if (unit.unverifiableSince() != null) {
            return false;
        }
        return !overlay.contains(feature);
    }

    @Override
    public Optional<String> setting(String key) {
        if (FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE.equals(key)
                && catalog.databaseManaged(Feature.NF02_CUSTOMER_PORTAL)) {
            return holder.get().setting(key);
        }
        return fallback.setting(key);
    }

    @Override
    public long generation() {
        return holder.get().generation();
    }

    @Override
    public boolean database() {
        return true;
    }

    @Override
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            holder.addListener(snapshot -> listener.run());
        }
    }

    /**
     * 閉じている理由の code（開いていれば空。画面の実効値の表示。design §15.1 の review P3-10）: {@code snapshot.stale}・
     * {@code master.closed}・{@code state.<状態>}（理由の code があれば添える）・{@code signoff.window.closed}（期限の経過で自動閉鎖の
     * 書込みの前）・{@code evaluation.unverifiable}・{@code overlay.<理由>}。
     */
    @Override
    public List<String> closedReasons(Feature feature) {
        if (!catalog.databaseManaged(feature)) {
            return fallback.closedReasons(feature);
        }
        List<String> reasons = new ArrayList<>();
        FeatureEnablementSnapshot snapshot = holder.get();
        if (stale(snapshot)) {
            reasons.add("snapshot.stale");
        }
        if (!snapshot.masterAllowed()) {
            reasons.add("master.closed");
        }
        FeatureEnablementSnapshot.UnitView unit = snapshot.unit(feature);
        if (unit.state() != EnablementState.OPEN) {
            reasons.add("state." + unit.state().name()
                    + (unit.closedReason() == null ? "" : "(" + unit.closedReason() + ")"));
        } else if (!today().isBefore(unit.openUntil())) {
            reasons.add("signoff.window.closed");
        }
        if (unit.unverifiableSince() != null) {
            reasons.add("evaluation.unverifiable");
        }
        for (String reason : overlay.reasons(feature)) {
            reasons.add("overlay." + reason);
        }
        return List.copyOf(reasons);
    }

    /** snapshot が古いか（最後に読めた時刻から max-staleness を超えた。一度も読めていない snapshot も古い）。 */
    public boolean stale(FeatureEnablementSnapshot snapshot) {
        Instant verifiedAt = snapshot.verifiedAt();
        return verifiedAt == null
                || Duration.between(verifiedAt, clock.instant()).compareTo(properties.getMaxStaleness()) > 0;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(JST));
    }
}
