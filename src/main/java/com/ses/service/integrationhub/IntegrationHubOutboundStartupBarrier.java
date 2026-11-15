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

/** OFF観測と再開境界を永続化する。失敗はoutboundだけを停止し、アプリ全体へ伝播させない。 */
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

    /** web serverより前に境界を確定する。DB障害時は後続の定期処理で再試行する。 */
    @Override public synchronized void start() { running = true; initializeSafely(); }
    @Override public void stop() { running = false; ready = false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MIN_VALUE; }
    @Override public void run(ApplicationArguments args) { continueRecovery(); }

    /** app.scheduling.enabled=falseでは登録されない。OFFの永続化失敗も再試行する。 */
    @Scheduled(fixedDelay = 5000)
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "integrationHubOutboundState", lockAtMostFor = "PT1M")
    public synchronized void continueRecovery() {
        if (!running) return;
        if (!initialized) initializeSafely();
        if (!initialized || ready || !enabled()) return;
        try { quarantineBatch(); }
        catch (RuntimeException failure) { ready = false; confirmationPending = true; warn("STATE_IO_ERROR"); }
    }

    private void initializeSafely() {
        try {
            State initializedState = transactions.execute(tx -> {
                SystemConfig row = configs.selectByIdForUpdate(CONFIG_KEY);
                if (!enabled()) {
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
                confirmationPending = enabled() && !ready; initialized = true;
            }
        } catch (RuntimeException failure) {
            initialized = false; ready = false; confirmationPending = true; warn("STATE_IO_ERROR");
        }
    }

    public boolean isReady() { return ready && running && enabled(); }
    public boolean isHistorical(Long id) { return !ready && id != null && id <= highWater; }

    /** SELECTは非locking。状態行の短いlockと各deliveryのversion CASで二重隔離を防ぐ。 */
    public synchronized int quarantineBatch() {
        if (!running || !initialized || ready || !enabled()) return 0;
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

    private boolean enabled() { return Boolean.TRUE.equals(properties.getExternalTransport().getEnabled()); }
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
