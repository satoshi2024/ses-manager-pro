package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.SystemConfig;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.AuditLogService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * OFF観測と再開境界を永続化する。失敗はoutboundだけを停止し、アプリ全体へ伝播させない。
 *
 * <p>起動時と実行時の分離（feature-enablement-console design §9.3。FE-D04）: 起動時の経路（{@link #start} → 初期化）は起動時の値
 * （{@link #bootEnabled}）で分岐し、実行時の {@link #isReady}・{@link #continueRecovery}・{@link #quarantineBatch} は runtime switch
 * （{@code IntegrationHubRuntimeSwitch}。無ければ properties の値）で判定する。{@link #continueRecovery} は起動時の値が開で実行時に
 * 閉じていれば初期化を再試行しない（起動時の初期化が失敗した後に確定の閉鎖で行が OFF に戻った場合に、起動時の値で
 * {@code QUARANTINING} へ書き換えて再承認に要る OFF の記録を壊さないため。review P3-N2）。outbound を閉じる transaction は
 * {@link #recordRuntimeOff} で行を {@code v1|OFF|0|<指紋>} に書き直し {@code NF05_OUTBOUND_OFF} を監査する（commit の後に memory の
 * {@code ready} を false にする。従来の kill switch は行を ON のまま残し、次に開く起動が隔離を飛ばした）。</p>
 */
@Component
public class IntegrationHubOutboundStartupBarrier implements ApplicationRunner, SmartLifecycle {
    public static final String CONFIG_KEY = "integration.hub.outbound.runtime.v1";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IntegrationHubOutboundStartupBarrier.class);
    private final ApiDeliveryMapper deliveries;
    private final SystemConfigMapper configs;
    private final AuditLogService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final IntegrationHubExternalApiProperties properties;
    private final MeterRegistry registry;
    /** 実行時の判定（Spring 管理外で {@code new} した試験では無く、properties の値を使う）。 */
    private volatile com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch runtimeSwitch;
    private volatile boolean running;
    private volatile boolean ready;
    private volatile boolean initialized;
    private volatile boolean confirmationPending = true;
    private volatile long highWater;
    private long nextWarning;

    public IntegrationHubOutboundStartupBarrier(ApiDeliveryMapper deliveries, AuditLogService audit,
            PlatformTransactionManager manager, Clock clock, SystemConfigMapper configs,
            IntegrationHubExternalApiProperties properties, ObjectProvider<MeterRegistry> registries) {
        this.deliveries = deliveries; this.audit = audit; this.configs = configs;
        this.transactions = new TransactionTemplate(manager); this.transactions.setTimeout(5);
        this.clock = clock; this.properties = properties; this.registry = registries.getIfAvailable();
        if (registry != null) {
            registry.gauge("integration_hub_outbound_ready", this, value -> value.ready ? 1 : 0);
            registry.gauge("integration_hub_startup_quarantine_pending", this, value -> value.confirmationPending ? 1 : 0);
        }
    }

    /** 実行時の判定を受け取る（Spring context の構築）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeSwitch(com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch runtimeSwitch) {
        this.runtimeSwitch = runtimeSwitch;
    }

    /** web serverより前に境界を確定する。DB障害時は後続の定期処理で再試行する。 */
    @Override public synchronized void start() { running = true; initializeSafely(); }
    @Override public void stop() { running = false; ready = false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MIN_VALUE; }
    @Override public void run(ApplicationArguments args) { continueRecovery(); }

    /**
     * app.scheduling.enabled=falseでは登録されない。OFFの永続化失敗も再試行する。起動時の値が開で実行時に閉じていれば、初期化を
     * 再試行しない（review P3-N2）。
     */
    @Scheduled(fixedDelay = 5000)
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "integrationHubOutboundState", lockAtMostFor = "PT1M")
    public synchronized void continueRecovery() {
        if (!running) return;
        drainUnsentAfterDefinitiveClosure();
        if (bootEnabled() && !runtimeOpen()) return;
        if (!initialized) initializeSafely();
        if (!initialized || ready || !runtimeOpen()) return;
        try { quarantineBatch(); }
        catch (RuntimeException failure) { ready = false; confirmationPending = true; warn("STATE_IO_ERROR"); }
    }

    /**
     * DB の開放状態が確定の閉鎖（{@code AUTO_CLOSED}・手動の閉鎖・総スイッチ）の間、未送信（{@code PENDING}・{@code RETRYABLE}）と lease の
     * 切れた {@code CLAIMED} の配信を1周期に128件まで、開き直しの起動の隔離と同じ DLQ {@code STARTUP_REVALIDATION_REQUIRED} にする
     * （{@link ApiDeliveryMapper#quarantineHistorical}。feature-enablement-console design §9.6。review rb-fe-d P2-2・P2-N2）。隔離を前倒しし、
     * 保存期間の purge（D7 の hold の確認を通る）と ops runbook §13.3 の条件付き bulk replay（開き直した後）の対象にする（NF05 を開き
     * 直さなければ外部へ送るための写しが無期限に残るため）。一時の閉鎖（DB は {@code OPEN}）・書込み待ち・property の源では何もしない
     * （producer は一時の閉鎖の間も積み続ける。P2-N3）。失敗は次の周期で再試行する。1件も無かった後は {@value #DRAIN_IDLE_MINUTES} 分
     * 照会しない（閉じた状態が続く間に5秒ごとに照会し続けない。確定の閉鎖でなくなれば間隔を戻す。review rb-fe-d P3-N1）。
     *
     * @return DLQ にした件数
     */
    public int drainUnsentAfterDefinitiveClosure() {
        if (runtimeSwitch == null
                || !runtimeSwitch.definitivelyClosedInDatabase(com.ses.config.feature.Feature.NF05_OUTBOUND)) {
            nextDrainNanos = 0;
            return 0;
        }
        long nowNanos = System.nanoTime();
        if (nextDrainNanos != 0 && nowNanos - nextDrainNanos < 0) {
            return 0;
        }
        try {
            Integer closed = transactions.execute(tx -> {
                LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
                int count = 0; Long last = null;
                for (var delivery : deliveries.selectHistoricalForQuarantine(Long.MAX_VALUE, now, 128)) {
                    int updated = deliveries.quarantineHistorical(delivery.getId(), delivery.getVersion(), now,
                            now.plusDays(90));
                    count += updated; if (updated > 0) last = delivery.getId();
                }
                if (count > 0) record("NF05_OUTBOUND_CLOSED_DLQ", last, "OFF");
                return count;
            });
            int count = closed == null ? 0 : closed;
            nextDrainNanos = count > 0 ? 0 : nowNanos + java.util.concurrent.TimeUnit.MINUTES.toNanos(DRAIN_IDLE_MINUTES);
            return count;
        } catch (RuntimeException failure) {
            warn("CLOSED_DLQ_FAILED");
            return 0;
        }
    }

    /** 確定の閉鎖の後の未送信が1件も無かった後に照会しない分数（P3-N1）。 */
    static final long DRAIN_IDLE_MINUTES = 5;
    /** 次に照会してよい時刻（{@link System#nanoTime}。0 は直ちに）。 */
    private volatile long nextDrainNanos;

    /**
     * outbound を閉じる transaction（手動・自動・総スイッチ）の中で、行を lock して {@code v1|OFF|0|<指紋>} に書き直し、変われば
     * {@code NF05_OUTBOUND_OFF} を同じ transaction で監査する（{@code recordDomainEventRequired}。失敗すれば閉鎖ごと rollback）。
     * commit の後に memory の {@code ready} を false にする。barrier の monitor を取らない（{@link #quarantineBatch} が行の lock を待つ間に
     * monitor を持つため。行の lock だけで直列になる）。
     */
    public void recordRuntimeOff() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("NF05 outbound の閉鎖は transaction の中で記録する");
        }
        SystemConfig row = configs.selectByIdForUpdate(CONFIG_KEY);
        State off = new State("OFF", 0, fingerprint());
        boolean changed = row == null || !off.value().equals(row.getConfigValue());
        if (row == null) configs.insert(new SystemConfig(CONFIG_KEY, off.value(), "NF05 outbound実行状態（直接編集禁止）"));
        else if (changed) { row.setConfigValue(off.value()); configs.updateById(row); }
        if (changed) record("NF05_OUTBOUND_OFF", 0L, "OFF");
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { ready = false; confirmationPending = false; }
                });
    }

    private void initializeSafely() {
        try {
            State initializedState = transactions.execute(tx -> {
                SystemConfig row = configs.selectByIdForUpdate(CONFIG_KEY);
                if (!bootEnabled()) {
                    State off = new State("OFF", 0, fingerprint());
                    boolean changed = row == null || !off.value().equals(row.getConfigValue());
                    if (row == null) configs.insert(new SystemConfig(CONFIG_KEY, off.value(), "NF05 outbound実行状態（直接編集禁止）"));
                    else if (!off.value().equals(row.getConfigValue())) { row.setConfigValue(off.value()); configs.updateById(row); }
                    if (changed) record("NF05_OUTBOUND_OFF", 0L, "OFF");
                    return off;
                }
                if (row == null) { warn("MISSING_STATE"); return null; }
                State state = State.parse(row.getConfigValue());
                if (!fingerprint().equals(state.tenant())) { warn("TENANT_STATE_MISMATCH"); return null; }
                if ("OFF".equals(state.phase())) {
                    state = new State("QUARANTINING", deliveries.selectHighWater(), fingerprint());
                    row.setConfigValue(state.value()); configs.updateById(row);
                    record("NF05_OUTBOUND_REOPEN", state.water(), "QUARANTINING");
                }
                return state;
            });
            if (initializedState != null) {
                highWater = initializedState.water(); ready = "ON".equals(initializedState.phase());
                confirmationPending = bootEnabled() && !ready; initialized = true;
            }
        } catch (RuntimeException failure) {
            initialized = false; ready = false; confirmationPending = true; warn("STATE_IO_ERROR");
        }
    }

    public boolean isReady() { return ready && running && runtimeOpen(); }
    public boolean isHistorical(Long id) { return !ready && id != null && id <= highWater; }

    /** SELECTは非locking。状態行の短いlockと各deliveryのversion CASで二重隔離を防ぐ。 */
    public synchronized int quarantineBatch() {
        if (!running || !initialized || ready || !runtimeOpen()) return 0;
        Integer changed = transactions.execute(tx -> {
            SystemConfig row = configs.selectByIdForUpdate(CONFIG_KEY);
            if (row == null) throw new IllegalStateException("NF05状態がありません");
            State state = State.parse(row.getConfigValue());
            if (!fingerprint().equals(state.tenant()) || "OFF".equals(state.phase()))
                throw new IllegalStateException("NF05状態が一致しません");
            if ("ON".equals(state.phase())) return -1;
            highWater = state.water();
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
            int count = 0; Long last = null;
            for (var delivery : deliveries.selectHistoricalForQuarantine(highWater, now, 128)) {
                int updated = deliveries.quarantineHistorical(delivery.getId(), delivery.getVersion(), now, now.plusDays(90));
                count += updated; if (updated > 0) last = delivery.getId();
            }
            if (count > 0) record("NF05_STARTUP_QUARANTINE", last, "QUARANTINING");
            if (!deliveries.hasHistoricalActive(highWater)) {
                row.setConfigValue(new State("ON", 0, fingerprint()).value()); configs.updateById(row);
                record("NF05_OUTBOUND_READY", 0L, "ON");
                return -1;
            }
            return count;
        });
        // DB commitとrequired auditの成功後だけmemory gateを開ける。
        if (changed != null && changed < 0) { highWater = 0; ready = true; confirmationPending = false; return 0; }
        warn("QUARANTINE_PENDING");
        return changed == null ? 0 : changed;
    }

    /** 起動時の値（起動時の経路だけが使う。FE-D04）。 */
    private boolean bootEnabled() { return com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.outboundBootOpen(properties); }
    /** 実行時の判定（確定・一時のどちらの閉鎖でも閉）。 */
    private boolean runtimeOpen() {
        return com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.outboundClosure(runtimeSwitch, properties)
                == com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch.Closure.NONE;
    }
    private String fingerprint() {
        String tenant = properties.getTopology().getBoundTenantId();
        return IntegrationHubDigest.sha256Hex(tenant == null ? "" : tenant);
    }
    private void record(String code, Long reference, String phase) {
        AuditLog entry = new AuditLog();
        entry.setUsername("nf05-runtime"); entry.setMethod("SYSTEM");
        entry.setUri("/integration-hub/outbound-state"); entry.setStatus(200);
        entry.setApplicationCode(code); entry.setSuccessFlag(true); entry.setReferenceType("NF05");
        entry.setReferenceId(reference); entry.setActorType("SYSTEM"); entry.setConfirmationSource("SCHEDULER_POLL");
        entry.setAfterState(phase); entry.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0));
        audit.recordDomainEventRequired(entry);
    }
    private void warn(String reason) {
        if (registry != null) registry.counter("integration_hub_outbound_state_events_total", "reason", reason).increment();
        if (System.nanoTime() >= nextWarning) {
            nextWarning = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
            log.warn("NF05 outboundは準備未完了です。reason={}", reason);
        }
    }
    private record State(String phase, long water, String tenant) {
        String value() { return "v1|" + phase + "|" + water + "|" + tenant; }
        static State parse(String value) {
            if (value == null || !value.matches("v1[|](OFF|ON|QUARANTINING)[|][0-9]{1,19}[|][0-9a-f]{64}"))
                throw new IllegalStateException("NF05状態の形式が不正です");
            String[] parts = value.split("[|]");
            return new State(parts[1], Long.parseLong(parts[2]), parts[3]);
        }
    }
}
