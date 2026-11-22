package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 機能の開放の metric（design §7.5）。tag に ID・氏名・値を載せない（開放単位・状態・理由の code だけ）。
 *
 * <ul>
 *   <li>{@code ses.feature.enablement.open{feature}}: 実効値（0/1。{@code FeatureSwitches#isEnabled}）</li>
 *   <li>{@code ses.feature.enablement.state{feature,state}}: DB の開放状態（0/1）</li>
 *   <li>{@code ses.feature.enablement.auto.closed{feature,reason}}: 自動閉鎖の件数</li>
 *   <li>{@code ses.feature.enablement.snapshot.age.seconds}: 最後に DB を読めた時刻からの秒数（一度も読めていなければ -1）</li>
 *   <li>{@code ses.feature.enablement.master.allowed}: 総スイッチ（0/1）</li>
 *   <li>{@code ses.feature.enablement.sign.off.days.remaining{feature}}: 要る署名の最も早い失効日までの日数（要る署名が欠けていれば -1）</li>
 * </ul>
 */
@Component
public class FeatureEnablementMetrics {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final ObjectProvider<MeterRegistry> registryProvider;
    private final ObjectProvider<FeatureSwitches> switchesProvider;
    private final FeatureEnablementSnapshotHolder holder;
    private final FeatureEnablementProperties properties;
    private final Clock clock;

    public FeatureEnablementMetrics(ObjectProvider<MeterRegistry> registryProvider,
                                    ObjectProvider<FeatureSwitches> switchesProvider,
                                    FeatureEnablementSnapshotHolder holder, FeatureEnablementProperties properties,
                                    Clock clock) {
        this.registryProvider = registryProvider;
        this.switchesProvider = switchesProvider;
        this.holder = holder;
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void register() {
        MeterRegistry registry = registryProvider == null ? null : registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        for (Feature feature : Feature.values()) {
            Gauge.builder("ses.feature.enablement.open", () -> open(feature)).tag("feature", feature.name())
                    .register(registry);
            Gauge.builder("ses.feature.enablement.sign.off.days.remaining", () -> daysRemaining(feature))
                    .tag("feature", feature.name()).register(registry);
            for (EnablementState state : EnablementState.values()) {
                Gauge.builder("ses.feature.enablement.state", () -> holder.get().unit(feature).state() == state ? 1 : 0)
                        .tag("feature", feature.name()).tag("state", state.name()).register(registry);
            }
        }
        Gauge.builder("ses.feature.enablement.snapshot.age.seconds", this::snapshotAgeSeconds).register(registry);
        Gauge.builder("ses.feature.enablement.master.allowed", () -> properties.isAllowed() ? 1 : 0).register(registry);
    }

    /** 自動閉鎖を数える（理由は固定の code）。 */
    public void autoClosed(Feature feature, String reason) {
        MeterRegistry registry = registryProvider == null ? null : registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        try {
            Counter.builder("ses.feature.enablement.auto.closed").tag("feature", feature.name())
                    .tag("reason", reason == null ? "UNKNOWN" : reason).register(registry).increment();
        } catch (RuntimeException ignored) {
            // metric の失敗は遷移を変えない
        }
    }

    private double open(Feature feature) {
        FeatureSwitches switches = switchesProvider == null ? null : switchesProvider.getIfAvailable();
        return switches != null && switches.isEnabled(feature) ? 1 : 0;
    }

    private double daysRemaining(Feature feature) {
        LocalDate openUntil = holder.get().unit(feature).openUntil();
        if (openUntil == null || openUntil.equals(LocalDate.MIN)) {
            return -1;
        }
        if (openUntil.equals(LocalDate.MAX)) {
            return Double.MAX_VALUE;
        }
        return ChronoUnit.DAYS.between(LocalDate.now(clock.withZone(JST)), openUntil);
    }

    private double snapshotAgeSeconds() {
        if (!holder.loaded()) {
            return -1;
        }
        return Duration.between(holder.get().verifiedAt(), clock.instant()).toSeconds();
    }
}
