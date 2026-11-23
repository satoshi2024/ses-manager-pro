package com.ses.privacy.production;

import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureRuntimeCondition;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.featureenablement.state.FeatureEnablementStateSource;
import com.ses.featureenablement.state.PropertyFeatureEnablementStateSource;
import com.ses.privacy.production.PrivacyAccessGuard.Capability;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * NF07 決定ID束縛の有効期間監視と実行時の公開条件（W3-14b、NF08 {@code AiExternalSendApprovalMonitor} と同じ方式）。
 *
 * <p>署名日前・失効のように時間の経過だけで生じる不足では起動を止めない。その間 NF07 は OFF として扱う: 本 class は
 * {@link FeatureRuntimeCondition} として {@code FeatureSwitches#isEnabled(Feature.NF07_RETENTION)} に組み込まれ、
 * {@code FeatureSwitchFilter}・{@code PrivacyAccessGuard}・policy source・sidebar は全て OFF と同じ判定（404）になる。
 * DSAR には {@link PrivacyDsarDecisionWindowCondition} が同じ判定を掛ける。</p>
 *
 * <p>状態は capability ごとに持つ（feature-enablement-console design §8.5 の 5。review P1-2）: 保持・保全（{@code retention}）は
 * D-01・D-09 の期間だけ、DSAR は D-01・D-06・D-09 の期間で判定する（D-06 の失効で保持・保全を閉じない。D-4）。構造（形式・tenant・
 * 承認 capability・digest）は D-09 が承認した capability の決定の全てで判定する。</p>
 *
 * <p>開いている capability は開放状態の源（{@link FeatureEnablementStateSource#isOpen}）から読む（{@code FeatureSwitches#isEnabled}
 * を呼ぶと本 class 自身の実行時条件で再帰するため）。承認の値は承認の値の源（{@link PrivacyApprovalSource}）から読む。判定の結果は
 * （JST の業務日、開放状態の世代、開いている capability の集合）を key に再利用する: 同じ日に開いた・DSAR を足した・署名を差し替えた
 * （世代が変わる）時は直ちに評価し直す。DB の源では有効期限の閉鎖は snapshot の {@code openUntil} でも効くが、metric と WARN の
 * 日ごとの出力のために本 class を残す。</p>
 *
 * <p>状態は次の2系統で知らせる。</p>
 * <ul>
 *   <li>metric（Prometheus、scrape時に評価）: {@code ses_privacy_decision_binding_state{state="..."}}（開いている capability の全体の
 *       現在の状態だけ1）、{@code ses_privacy_decision_binding_days_remaining}（最も早い失効日までのJST暦日数。未設定はNaN）。
 *       tagは有限の状態名だけで、決定ID・tenant・PIIを載せない。</li>
 *   <li>WARN log: 起動時と、判定・scrape時に状態またはJST日付が変わった時に1回（固定コード {@link #LOG_CODE}）。</li>
 * </ul>
 * scheduler は追加しない（NF08 と同じく、metric取得と入口の判定が再評価の契機になる）。
 */
@Component
@Profile("prod")
public class PrivacyDecisionWindowMonitor implements FeatureRuntimeCondition, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PrivacyDecisionWindowMonitor.class);

    public static final String STATE_METRIC = "ses.privacy.decision.binding.state";
    public static final String DAYS_REMAINING_METRIC = "ses.privacy.decision.binding.days.remaining";
    public static final String STATE_TAG = "state";
    public static final String LOG_CODE = "NF07_PRIVACY_DECISION_STATE";
    public static final int DEFAULT_WARNING_DAYS = 30;

    /** 監視上の状態。NF07 を公開してよいのは {@link #permitsAccess()} が true の状態だけ。 */
    public enum State {
        /** 対象の capability が開いていない（監視対象外）。 */
        DISABLED,
        /** 束縛が完全で、失効まで警告日数より多い。 */
        VALID,
        /** 束縛が完全だが、失効まで警告日数以内（再承認の準備が必要）。 */
        EXPIRING,
        /** 失効日を過ぎ、NF07 は OFF として扱い中。 */
        EXPIRED,
        /** 署名日前で、NF07 は OFF として扱い中。 */
        NOT_YET_VALID,
        /** 束縛が不完全。NF07 は OFF として扱う。 */
        INCOMPLETE;

        public boolean requiresAttention() {
            return this != DISABLED && this != VALID;
        }

        public boolean permitsAccess() {
            return this == VALID || this == EXPIRING;
        }
    }

    /** 監視結果。daysRemainingは最も早い失効日までのJST暦日数（不明はnull）。 */
    public record Status(State state, Long daysRemaining, LocalDate businessDate) {
    }

    /** 判定の再利用の key（JST の業務日、開放状態の世代、開いている capability）。 */
    record CacheKey(LocalDate businessDate, long generation, Set<Capability> open) {
    }

    /** 再利用する判定（全体と capability ごと）。 */
    private record Cached(CacheKey key, Status overall, Map<Capability, Status> perCapability) {
    }

    private final FeatureEnablementStateSource stateSource;
    private final PrivacyApprovalSource approvalSource;
    private final OidcSecurityProperties oidcProperties;
    private final Clock clock;
    private final Supplier<MeterRegistry> registrySupplier;
    private final AtomicReference<Cached> cached = new AtomicReference<>();
    private final AtomicReference<String> lastReported = new AtomicReference<>();

    @Autowired
    public PrivacyDecisionWindowMonitor(FeatureEnablementStateSource stateSource, PrivacyApprovalSource approvalSource,
                                        OidcSecurityProperties oidcProperties, Clock clock,
                                        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(stateSource, approvalSource, oidcProperties, clock,
                (Supplier<MeterRegistry>) meterRegistryProvider::getIfAvailable);
    }

    /** 単体テスト用。MeterRegistryを直接渡す（nullならmetricを登録しない）。 */
    public PrivacyDecisionWindowMonitor(FeatureEnablementStateSource stateSource, PrivacyApprovalSource approvalSource,
                                        OidcSecurityProperties oidcProperties, Clock clock, MeterRegistry meterRegistry) {
        this(stateSource, approvalSource, oidcProperties, clock, (Supplier<MeterRegistry>) () -> meterRegistry);
    }

    /** 単体テスト用。property の源（{@code privacy.*} の flag と {@code privacy.approval.*}）で包む。 */
    public PrivacyDecisionWindowMonitor(PrivacyFeatureProperties featureProperties,
                                        PrivacyApprovalProperties approvalProperties,
                                        OidcSecurityProperties oidcProperties, Clock clock,
                                        MeterRegistry meterRegistry) {
        this(propertyStateSource(featureProperties), PrivacyApprovalSource.properties(approvalProperties, featureProperties),
                oidcProperties, clock, (Supplier<MeterRegistry>) () -> meterRegistry);
    }

    private PrivacyDecisionWindowMonitor(FeatureEnablementStateSource stateSource, PrivacyApprovalSource approvalSource,
                                         OidcSecurityProperties oidcProperties, Clock clock,
                                         Supplier<MeterRegistry> registrySupplier) {
        this.stateSource = stateSource;
        this.approvalSource = approvalSource;
        this.oidcProperties = oidcProperties;
        this.clock = clock == null ? Clock.system(PrivacyDecisionBinding.BUSINESS_ZONE) : clock;
        this.registrySupplier = registrySupplier;
    }

    /** {@code privacy.*} の flag を property の開放状態の源で読む（{@code FeatureSwitchProperties#toggle} と同じ値）。 */
    static FeatureEnablementStateSource propertyStateSource(PrivacyFeatureProperties featureProperties) {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.usePrivacyFeatureProperties(featureProperties);
        return new PropertyFeatureEnablementStateSource(properties);
    }

    /**
     * 全 singleton の生成後に metric を登録し、起動時の状態を報告する。本 bean は {@code FeatureSwitches} へ注入されるため、
     * その生成中に MeterRegistry を取りに行かない（registry 側の binder 経由の循環参照を避ける）。
     */
    @Override
    public void afterSingletonsInstantiated() {
        start();
    }

    void start() {
        registerMetrics();
        reportIfChanged();
    }

    @Override
    public Feature feature() {
        return Feature.NF07_RETENTION;
    }

    /** 今日（JST業務日）の保持・保全（retention）の束縛が完全な場合だけ公開を許す。状態の変化は WARN log へも反映する。 */
    @Override
    public boolean satisfied() {
        return satisfied(Capability.RETENTION);
    }

    /** capability ごとの公開の可否（DSAR は {@link PrivacyDsarDecisionWindowCondition} が使う）。 */
    public boolean satisfied(Capability capability) {
        reportIfChanged();
        return currentStatus(capability).state().permitsAccess();
    }

    @Override
    public Optional<String> unsatisfiedReason() {
        return reason(Capability.RETENTION);
    }

    /** 公開を許さない理由の code（{@code decisionWindow.<状態>}。許すなら空）。 */
    public Optional<String> reason(Capability capability) {
        State state = currentStatus(capability).state();
        return state.permitsAccess() ? Optional.empty()
                : Optional.of("decisionWindow." + state.name());
    }

    /** 失効前警告の日数（設定値を1〜366日に丸める）。 */
    public int warningDays() {
        int configured = approvalSource == null ? DEFAULT_WARNING_DAYS : approvalSource.expiryWarningDays();
        return Math.max(1, Math.min(PrivacyDecisionBinding.MAX_VALIDITY_DAYS, configured));
    }

    /**
     * 開いている capability の全体の状態（metric と WARN log）。（JST業務日、世代、開いている capability）が変わるまでは同じ結果を返す。
     */
    public Status currentStatus() {
        return cachedFor(currentKey()).overall();
    }

    /** capability ごとの状態（開いていなければ {@link State#DISABLED}）。 */
    public Status currentStatus(Capability capability) {
        Cached current = cachedFor(currentKey());
        Status status = capability == null ? null : current.perCapability().get(capability);
        return status == null ? new Status(State.DISABLED, null, current.key().businessDate()) : status;
    }

    /**
     * 状態またはJST日付が前回の報告から変わっていれば、要対応の状態をWARN、正常をINFOで1回だけ出す。
     * 起動時・入口の判定時・metric取得時に呼ばれ、同じ状態・同じ日の重複出力はしない。
     */
    public Status reportIfChanged() {
        Status status = currentStatus();
        String key = status.state() + "@" + status.businessDate();
        String previous = lastReported.getAndSet(key);
        if (key.equals(previous)) {
            return status;
        }
        if (status.state().requiresAttention()) {
            log.warn("{}: NF07の決定ID束縛の状態={} 失効までの日数={} 警告日数={}（EXPIRING以外はNF07をOFFとして扱い中）",
                    LOG_CODE, status.state(), status.daysRemaining() == null ? "-" : status.daysRemaining(),
                    warningDays());
        } else if (status.state() == State.VALID && previous == null) {
            log.info("{}: NF07の決定ID束縛の状態={} 失効までの日数={}", LOG_CODE, status.state(), status.daysRemaining());
        }
        return status;
    }

    private CacheKey currentKey() {
        LocalDate today = PrivacyDecisionBinding.businessDate(clock);
        long generation;
        try {
            generation = stateSource == null ? 0L : stateSource.generation();
        } catch (RuntimeException e) {
            generation = Long.MIN_VALUE;
        }
        return new CacheKey(today, generation, openCapabilities());
    }

    private Cached cachedFor(CacheKey key) {
        Cached previous = cached.get();
        if (previous != null && previous.key().equals(key)) {
            return previous;
        }
        PrivacyApprovalSource values = approvalSource == null ? null : approvalSource.resolve();
        Map<Capability, Status> perCapability = new EnumMap<>(Capability.class);
        for (Capability capability : key.open()) {
            perCapability.put(capability, evaluate(values, key.businessDate(), EnumSet.of(capability)));
        }
        Cached computed = new Cached(key, evaluate(values, key.businessDate(), key.open()),
                Collections.unmodifiableMap(perCapability));
        cached.set(computed);
        return computed;
    }

    /** {@code enabled} を開き、期間も {@code enabled} が要る決定だけで判定した状態。 */
    private Status evaluate(PrivacyApprovalSource values, LocalDate today, Set<Capability> enabled) {
        if (enabled.isEmpty()) {
            return new Status(State.DISABLED, null, today);
        }
        PrivacyDecisionBinding.Window window = PrivacyDecisionBinding.window(values, today, warningDays(), enabled);
        PrivacyDecisionBinding.Evaluation evaluation =
                PrivacyDecisionBinding.evaluate(values, enabled, authorizationTenantId(), today, enabled);
        State state;
        if (evaluation.complete()) {
            state = window.state() == PrivacyDecisionBinding.WindowState.EXPIRING ? State.EXPIRING : State.VALID;
        } else if (!PrivacyDecisionBinding.structuralViolations(evaluation).isEmpty()) {
            state = State.INCOMPLETE;
        } else if (window.state() == PrivacyDecisionBinding.WindowState.EXPIRED) {
            state = State.EXPIRED;
        } else if (window.state() == PrivacyDecisionBinding.WindowState.NOT_YET_VALID) {
            state = State.NOT_YET_VALID;
        } else {
            state = State.INCOMPLETE;
        }
        return new Status(state, window.daysRemaining(), today);
    }

    /** 開いている capability（開放状態の源を直接読む。{@code FeatureSwitches} を経由しない）。 */
    private Set<Capability> openCapabilities() {
        Set<Capability> open = EnumSet.noneOf(Capability.class);
        if (stateSource == null) {
            return Collections.unmodifiableSet(open);
        }
        try {
            if (stateSource.isOpen(Feature.NF07_RETENTION)) {
                open.add(Capability.RETENTION);
            }
            if (stateSource.isOpen(Feature.NF07_DSAR)) {
                open.add(Capability.DSAR);
            }
        } catch (RuntimeException e) {
            return Collections.unmodifiableSet(EnumSet.noneOf(Capability.class));
        }
        return Collections.unmodifiableSet(open);
    }

    /** {@code PrivacyProductionConfigurationValidator} と同じ設定tenant。 */
    private String authorizationTenantId() {
        String configured = oidcProperties == null ? null : oidcProperties.getTenantId();
        return StringUtils.hasText(configured) ? configured : "default";
    }

    private void registerMetrics() {
        MeterRegistry registry = registrySupplier == null ? null : registrySupplier.get();
        if (registry == null) {
            return;
        }
        for (State state : State.values()) {
            Gauge.builder(STATE_METRIC, this, monitor -> monitor.stateValue(state))
                    .tag(STATE_TAG, state.name())
                    .description("NF07 決定ID束縛の監視状態（現在の状態だけ1）")
                    .register(registry);
        }
        Gauge.builder(DAYS_REMAINING_METRIC, this, PrivacyDecisionWindowMonitor::daysRemainingValue)
                .description("NF07 決定ID束縛の最も早い失効日までのJST暦日数")
                .register(registry);
    }

    private double stateValue(State state) {
        try {
            return currentStatus().state() == state ? 1.0 : 0.0;
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }

    /** scrape周期で評価し、状態の変化をlogへも反映する（schedulerを持たないため）。 */
    private double daysRemainingValue() {
        try {
            Status status = reportIfChanged();
            return status.daysRemaining() == null ? Double.NaN : status.daysRemaining();
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }
}
