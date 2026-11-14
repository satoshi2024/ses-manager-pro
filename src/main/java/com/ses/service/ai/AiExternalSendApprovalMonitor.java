package com.ses.service.ai;

import com.ses.config.AiConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * NF08 外部送信承認の有効期間監視。失効前の警告、失効・署名日前・設定不完全を WARN log と監視metricで知らせる。
 * <p>承認が失効しても起動は止めず（{@code AiProductionSafetyValidator}）、外部送信だけが固定deny-allになる。
 * その状態を運用者が見落とさないよう、次の2系統で出す。</p>
 * <ul>
 *   <li>metric（Prometheus、scrape時に評価）: {@code ses_ai_external_send_approval_state{state="..."}}（現在の状態だけ1）、
 *       {@code ses_ai_external_send_approval_days_remaining}（失効日までのJST暦日数。未設定はNaN）。
 *       tagは有限の状態名だけで、決定ID・tenant・PIIを載せない。</li>
 *   <li>WARN log: 起動時と、scrape時に状態またはJST日付が変わった時に1回（同じ状態・同じ日は繰り返さない）。
 *       固定コード {@link #LOG_CODE} で検索できる。</li>
 * </ul>
 * <p>警告の時期は {@code ai.external-send-monitor.expiry-warning-days}（既定30日、1〜366日に丸める）で決める。
 * schedulerは追加しない（test profileのscheduler停止方針と独立に、metric取得側の周期で評価する）。
 * MeterRegistryを取得できない環境（slice test等）では起動時logだけを出す。</p>
 */
@Slf4j
@Component
public class AiExternalSendApprovalMonitor {

    public static final String STATE_METRIC = "ses.ai.external.send.approval.state";
    public static final String DAYS_REMAINING_METRIC = "ses.ai.external.send.approval.days.remaining";
    public static final String STATE_TAG = "state";
    public static final String LOG_CODE = "AI_EXTERNAL_SEND_APPROVAL_STATE";
    public static final int DEFAULT_WARNING_DAYS = 30;

    /** 監視上の状態。WARN対象は {@link #requiresAttention()} がtrueのもの。 */
    public enum State {
        /** external-sendが無効（監視対象外）。 */
        DISABLED,
        /** 承認済みで、失効まで警告日数より多い。 */
        VALID,
        /** 承認済みだが、失効まで警告日数以内（再承認の準備が必要）。 */
        EXPIRING,
        /** 失効日を過ぎ、外部送信は停止中。 */
        EXPIRED,
        /** 署名日前で、外部送信は停止中。 */
        NOT_YET_VALID,
        /** 承認設定・チェックリストが不完全で、外部送信は停止中。 */
        INCOMPLETE;

        public boolean requiresAttention() {
            return this != DISABLED && this != VALID;
        }
    }

    /** 監視結果。daysRemainingは失効日までのJST暦日数（不明はnull）。 */
    public record Status(State state, Long daysRemaining, LocalDate businessDate) {
    }

    private final AiConfig aiConfig;
    private final AiProductionApprovalGate approvalGate;
    private final Clock clock;
    private final Supplier<MeterRegistry> registrySupplier;
    private final AtomicReference<String> lastReported = new AtomicReference<>();

    @Autowired
    public AiExternalSendApprovalMonitor(AiConfig aiConfig, AiProductionApprovalGate approvalGate, Clock clock,
                                         ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(aiConfig, approvalGate, clock, (Supplier<MeterRegistry>) meterRegistryProvider::getIfAvailable);
    }

    /** 単体テスト用。MeterRegistryを直接渡す（nullならmetricを登録しない）。 */
    public AiExternalSendApprovalMonitor(AiConfig aiConfig, AiProductionApprovalGate approvalGate, Clock clock,
                                         MeterRegistry meterRegistry) {
        this(aiConfig, approvalGate, clock, (Supplier<MeterRegistry>) () -> meterRegistry);
    }

    private AiExternalSendApprovalMonitor(AiConfig aiConfig, AiProductionApprovalGate approvalGate, Clock clock,
                                          Supplier<MeterRegistry> registrySupplier) {
        this.aiConfig = aiConfig;
        this.approvalGate = approvalGate;
        this.clock = clock == null ? Clock.system(AiExternalSendApproval.BUSINESS_ZONE) : clock;
        this.registrySupplier = registrySupplier;
    }

    @PostConstruct
    void start() {
        registerMetrics();
        reportIfChanged();
    }

    /** 失効前警告の日数（設定値を1〜366日に丸める）。 */
    public int warningDays() {
        AiConfig.ExternalSendMonitor monitor = aiConfig == null ? null : aiConfig.getExternalSendMonitor();
        int configured = monitor == null ? DEFAULT_WARNING_DAYS : monitor.getExpiryWarningDays();
        return Math.max(1, Math.min(AiExternalSendApproval.MAX_VALIDITY_DAYS, configured));
    }

    /** 現在の状態を副作用なしで評価する。 */
    public Status currentStatus() {
        LocalDate today = AiExternalSendApproval.businessDate(clock);
        if (aiConfig == null || !aiConfig.isExternalSendEnabled()) {
            return new Status(State.DISABLED, null, today);
        }
        AiExternalSendApproval.Window window = AiExternalSendApproval.window(aiConfig, today, warningDays());
        AiProductionApprovalGate.AiExternalSendGateStatus gate =
                approvalGate == null ? null : approvalGate.externalSendStatus();
        State state;
        if (gate != null && gate.approved()) {
            state = window.state() == AiExternalSendApproval.WindowState.EXPIRING ? State.EXPIRING : State.VALID;
        } else if (window.state() == AiExternalSendApproval.WindowState.EXPIRED) {
            state = State.EXPIRED;
        } else if (window.state() == AiExternalSendApproval.WindowState.NOT_YET_VALID) {
            state = State.NOT_YET_VALID;
        } else {
            state = State.INCOMPLETE;
        }
        return new Status(state, window.daysRemaining(), today);
    }

    /**
     * 状態またはJST日付が前回の報告から変わっていれば、要対応の状態をWARN、正常をINFOで1回だけ出す。
     * 起動時とmetric取得時に呼ばれ、同じ状態・同じ日の重複出力はしない。
     */
    public Status reportIfChanged() {
        Status status = currentStatus();
        String key = status.state() + "@" + status.businessDate();
        String previous = lastReported.getAndSet(key);
        if (key.equals(previous)) {
            return status;
        }
        if (status.state().requiresAttention()) {
            log.warn("{}: AI外部送信承認の状態={} 失効までの日数={} 警告日数={}（EXPIRING以外は外部送信を停止中）",
                    LOG_CODE, status.state(), status.daysRemaining() == null ? "-" : status.daysRemaining(),
                    warningDays());
        } else if (status.state() == State.VALID && previous == null) {
            log.info("{}: AI外部送信承認の状態={} 失効までの日数={}", LOG_CODE, status.state(), status.daysRemaining());
        }
        return status;
    }

    private void registerMetrics() {
        MeterRegistry registry = registrySupplier == null ? null : registrySupplier.get();
        if (registry == null) {
            return;
        }
        for (State state : State.values()) {
            Gauge.builder(STATE_METRIC, this, monitor -> monitor.stateValue(state))
                    .tag(STATE_TAG, state.name())
                    .description("NF08 外部送信承認の監視状態（現在の状態だけ1）")
                    .register(registry);
        }
        Gauge.builder(DAYS_REMAINING_METRIC, this, AiExternalSendApprovalMonitor::daysRemainingValue)
                .description("NF08 外部送信承認の失効日までのJST暦日数")
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
