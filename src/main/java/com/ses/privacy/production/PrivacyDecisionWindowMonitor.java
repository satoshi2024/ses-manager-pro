package com.ses.privacy.production;

import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureRuntimeCondition;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * NF07 決定ID束縛の有効期間監視と実行時の公開条件（W3-14b、NF08 {@code AiExternalSendApprovalMonitor} と同じ方式）。
 *
 * <p>prod では、署名日前・失効のように時間の経過だけで生じる不足では起動を止めない
 * （{@link PrivacyProductionConfigurationValidator}）。その間 NF07 は OFF として扱う: 本 class は
 * {@link FeatureRuntimeCondition} として {@code FeatureSwitches#isEnabled(Feature.NF07_RETENTION)} に組み込まれ、
 * {@code FeatureSwitchFilter}・{@code PrivacyAccessGuard}・policy source・sidebar は全て OFF と同じ判定（404）になる。
 * 判定は呼出しのたびに JST 業務日で行い（同じ日の結果は再利用する）、実行中に失効日を迎えた日から閉じる。
 * 再承認の設定は再起動で反映する（設定値は起動時に一度だけ bind される）。
 *
 * <p>状態は次の2系統で知らせる。
 * <ul>
 *   <li>metric（Prometheus、scrape時に評価）: {@code ses_privacy_decision_binding_state{state="..."}}（現在の状態だけ1）、
 *       {@code ses_privacy_decision_binding_days_remaining}（最も早い失効日までのJST暦日数。未設定はNaN）。
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
        /** NF07 の flag が全て OFF（監視対象外）。 */
        DISABLED,
        /** 束縛が完全で、失効まで警告日数より多い。 */
        VALID,
        /** 束縛が完全だが、失効まで警告日数以内（再承認の準備が必要）。 */
        EXPIRING,
        /** 失効日を過ぎ、NF07 は OFF として扱い中。 */
        EXPIRED,
        /** 署名日前で、NF07 は OFF として扱い中。 */
        NOT_YET_VALID,
        /** 束縛が不完全（prod では起動時に止まるため、通常は到達しない）。NF07 は OFF として扱う。 */
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

    private final PrivacyFeatureProperties featureProperties;
    private final PrivacyApprovalProperties approvalProperties;
    private final OidcSecurityProperties oidcProperties;
    private final Clock clock;
    private final Supplier<MeterRegistry> registrySupplier;
    private final AtomicReference<Status> cached = new AtomicReference<>();
    private final AtomicReference<String> lastReported = new AtomicReference<>();

    @Autowired
    public PrivacyDecisionWindowMonitor(PrivacyFeatureProperties featureProperties,
                                        PrivacyApprovalProperties approvalProperties,
                                        OidcSecurityProperties oidcProperties, Clock clock,
                                        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(featureProperties, approvalProperties, oidcProperties, clock,
                (Supplier<MeterRegistry>) meterRegistryProvider::getIfAvailable);
    }

    /** 単体テスト用。MeterRegistryを直接渡す（nullならmetricを登録しない）。 */
    public PrivacyDecisionWindowMonitor(PrivacyFeatureProperties featureProperties,
                                        PrivacyApprovalProperties approvalProperties,
                                        OidcSecurityProperties oidcProperties, Clock clock,
                                        MeterRegistry meterRegistry) {
        this(featureProperties, approvalProperties, oidcProperties, clock,
                (Supplier<MeterRegistry>) () -> meterRegistry);
    }

    private PrivacyDecisionWindowMonitor(PrivacyFeatureProperties featureProperties,
                                         PrivacyApprovalProperties approvalProperties,
                                         OidcSecurityProperties oidcProperties, Clock clock,
                                         Supplier<MeterRegistry> registrySupplier) {
        this.featureProperties = featureProperties;
        this.approvalProperties = approvalProperties;
        this.oidcProperties = oidcProperties;
        this.clock = clock == null ? Clock.system(PrivacyDecisionBinding.BUSINESS_ZONE) : clock;
        this.registrySupplier = registrySupplier;
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

    /** 今日（JST業務日）の束縛が完全な場合だけ公開を許す。状態の変化は WARN log へも反映する。 */
    @Override
    public boolean satisfied() {
        return reportIfChanged().state().permitsAccess();
    }

    /** 失効前警告の日数（設定値を1〜366日に丸める）。 */
    public int warningDays() {
        int configured = approvalProperties == null ? DEFAULT_WARNING_DAYS : approvalProperties.getExpiryWarningDays();
        return Math.max(1, Math.min(PrivacyDecisionBinding.MAX_VALIDITY_DAYS, configured));
    }

    /**
     * 現在の状態を評価する（副作用は同じ日の結果の再利用だけ）。設定値は起動時に固定されるため、
     * JST業務日が変わるまでは同じ結果を返す。
     */
    public Status currentStatus() {
        LocalDate today = PrivacyDecisionBinding.businessDate(clock);
        Status previous = cached.get();
        if (previous != null && today.equals(previous.businessDate())) {
            return previous;
        }
        Status status = evaluate(today);
        cached.set(status);
        return status;
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

    private Status evaluate(LocalDate today) {
        Set<Capability> enabled = enabledCapabilities();
        if (enabled.isEmpty()) {
            return new Status(State.DISABLED, null, today);
        }
        PrivacyDecisionBinding.Window window = PrivacyDecisionBinding.window(approvalProperties, today, warningDays());
        PrivacyDecisionBinding.Evaluation evaluation =
                PrivacyDecisionBinding.evaluate(approvalProperties, enabled, authorizationTenantId(), today);
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

    private Set<Capability> enabledCapabilities() {
        Set<Capability> enabled = EnumSet.noneOf(Capability.class);
        if (featureProperties == null) {
            return enabled;
        }
        if (featureProperties.getRetention() != null && featureProperties.getRetention().isEnabled()) {
            enabled.add(Capability.RETENTION);
        }
        if (featureProperties.getDsar() != null && featureProperties.getDsar().isEnabled()) {
            enabled.add(Capability.DSAR);
        }
        return enabled;
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
