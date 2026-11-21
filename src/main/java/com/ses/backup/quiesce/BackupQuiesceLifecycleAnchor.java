package com.ses.backup.quiesce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 業務の {@code dataSource} が依存する最小の bean（design.md §6.11。D3-P2-1）。
 *
 * <p>制御用・heartbeat 用の接続 pool の holder、gate（依存の無い object）、instance の登録・heartbeat・STOPPED を書く部品だけを
 * 持ち、{@link DataSourceProperties} と {@link BackupQuiesceProperties}（と profile の判定に使う {@link Environment}）にだけ
 * 依存する。{@code BackupQuiesceDestructionOrderPostProcessor} が {@code dataSource} の依存にこの bean だけを足すため、
 * この bean と pool は業務の DataSource より後に破棄され、業務の pool を閉じた後でも STOPPED を書ける。
 *
 * <p>heartbeat の thread もこの bean が持つ（静止応答の bean が先に破棄されても STOPPED の直前まで heartbeat を続ける。
 * D4-P3-2 の補足）。
 *
 * <p>gate の働き方（§6.2・§12.2）:
 * <ul>
 *   <li>{@code enabled=true}（prod と prod 以外）: ARMED。登録と heartbeat を行い、静止応答が gate を開閉する。</li>
 *   <li>prod の {@code enabled=false}（停止口。U-7）: DISABLED。登録と heartbeat を続け、gate は閉じない。</li>
 *   <li>prod 以外の {@code enabled=false}（test profile）: INERT。pool を作らず、登録も heartbeat もしない。gate は常に OPEN。</li>
 * </ul>
 */
public class BackupQuiesceLifecycleAnchor implements DisposableBean {

    /** bean 名（{@code dataSource} の depends-on に使う）。 */
    public static final String BEAN_NAME = "backupQuiesceLifecycleAnchor";

    private static final Logger log = LoggerFactory.getLogger(BackupQuiesceLifecycleAnchor.class);
    private static final Duration THREAD_STOP_WAIT = Duration.ofSeconds(12);

    private final BackupQuiesceProperties properties;
    private final BackupWriteGate gate;
    private final BackupQuiesceControlConnectionPool pools;
    private final BackupQuiesceSignalRepository repository;
    private final String instanceId;
    private final String hostName;
    private final boolean schedulingEnabled;
    private final BackupQuiesceTicker heartbeatTicker = new BackupQuiesceTicker("backup-quiesce-heartbeat");

    private volatile boolean registered;
    private volatile boolean stopped;
    private volatile String instanceStatus = "STARTING";
    private volatile String dbClientHost = "";
    private volatile boolean fenceLogged;

    /** Spring が使う。prod の判定は {@code Environment.acceptsProfiles}（property だけを読まない）。 */
    public BackupQuiesceLifecycleAnchor(DataSourceProperties dataSourceProperties, BackupQuiesceProperties properties,
                                        Environment environment) {
        this.properties = Objects.requireNonNull(properties, "properties");
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        BackupWriteGate.Mode mode = properties.isEnabled() ? BackupWriteGate.Mode.ARMED
                : prod ? BackupWriteGate.Mode.DISABLED : BackupWriteGate.Mode.INERT;
        this.gate = new BackupWriteGate(mode, prod, properties.getFenceAfter(), properties.getDeferredWritesMax());
        this.schedulingEnabled = environment.getProperty("app.scheduling.enabled", Boolean.class, Boolean.TRUE);
        this.instanceId = UUID.randomUUID().toString();
        this.hostName = localHostName();
        if (mode == BackupWriteGate.Mode.INERT) {
            this.pools = null;
            this.repository = null;
        } else {
            this.pools = new BackupQuiesceControlConnectionPool(dataSourceProperties);
            this.repository = new JdbcBackupQuiesceSignalRepository(pools.controlTemplate(), pools.heartbeatTemplate(),
                    properties);
        }
    }

    /** 試験用（fake の repository と、時計を差し替えた gate）。 */
    BackupQuiesceLifecycleAnchor(BackupQuiesceProperties properties, BackupWriteGate gate,
                                 BackupQuiesceSignalRepository repository, boolean schedulingEnabled) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.pools = null;
        this.repository = repository;
        this.schedulingEnabled = schedulingEnabled;
        this.instanceId = UUID.randomUUID().toString();
        this.hostName = "test-host";
    }

    public BackupWriteGate gate() {
        return gate;
    }

    /** 登録と heartbeat を行うか（INERT 以外）。 */
    public boolean isActive() {
        return gate.mode() != BackupWriteGate.Mode.INERT;
    }

    BackupQuiesceProperties properties() {
        return properties;
    }

    BackupQuiesceSignalRepository repository() {
        return repository;
    }

    BackupQuiesceControlConnectionPool pools() {
        return pools;
    }

    public String instanceId() {
        return instanceId;
    }

    String dbClientHost() {
        return dbClientHost;
    }

    boolean schedulingEnabled() {
        return schedulingEnabled;
    }

    /** ack の {@code scheduler_state}（{@code APP_SCHEDULING_ENABLED=false} なら DISABLED。§11）。 */
    String schedulerState() {
        return schedulingEnabled ? "PAUSED" : "DISABLED";
    }

    boolean isRegistered() {
        return registered;
    }

    // ---------------------------------------------------------------- 登録・heartbeat

    /** instance を STARTING で登録する（最初の heartbeat。§6.8）。失敗は例外で返す（呼び出し側が起動を中止する）。 */
    void register() {
        BackupQuiesceSignalRepository.Registered result = repository.registerInstance(
                new BackupQuiesceSignalRepository.InstanceRegistration(instanceId, hostName, "unknown",
                        properties.getPollInterval().toMillis(), properties.getHeartbeatInterval().toMillis(),
                        properties.getFenceAfter().toMillis(), properties.getMaxHoldCheckpoint().toMillis(),
                        properties.getMaxHoldFull().toMillis(), schedulingEnabled, gate.state().name()));
        dbClientHost = result.dbClientHost();
        gate.recordHeartbeatSuccess();
        registered = true;
        log.info("backup の書込み静止に instance を登録しました（instance={}、host={}、接続元={}、gate={}）", instanceId,
                hostName, dbClientHost, gate.state());
    }

    /** heartbeat の thread を始める（専用の thread と heartbeat 用の接続。§5.2）。 */
    void startHeartbeat() {
        // 登録が最初の heartbeat なので、次は heartbeat の間隔の後に書く
        heartbeatTicker.start(properties.getHeartbeatInterval(), properties.getHeartbeatInterval(), this::heartbeatOnce);
    }

    /** heartbeat を1回書く（heartbeat の thread。heartbeat の UPDATE だけを行う）。 */
    void heartbeatOnce() {
        if (!registered || stopped) {
            return;
        }
        boolean written;
        try {
            written = repository.heartbeat(instanceId, instanceStatus, gate.state().name());
        } catch (RuntimeException failure) {
            log.debug("backup の書込み静止の heartbeat を書けませんでした", failure);
            written = false;
        }
        if (written) {
            boolean recovered = gate.recordHeartbeatSuccess();
            if (recovered || fenceLogged) {
                fenceLogged = false;
                log.warn("errorCode=BACKUP_QUIESCE_UNFENCED heartbeat を書けるようになったため、書込みの停止（self-fence）を解きました"
                        + "（instance={}）", instanceId);
            }
            return;
        }
        boolean fenced = gate.recordHeartbeatFailure();
        if (fenced && !fenceLogged) {
            fenceLogged = true;
            log.error("errorCode=BACKUP_QUIESCE_FENCED heartbeat を {} 秒書けないため、更新を止めました（self-fence。instance={}）。"
                    + "制御用・heartbeat 用の DB 接続を確かめてください", properties.getFenceAfter().getSeconds(), instanceId);
        } else if (!fenced) {
            log.warn("backup の書込み静止の heartbeat を書けませんでした（instance={}、失敗の累計={}）", instanceId,
                    gate.heartbeatFailureCount());
        }
    }

    /** 起動の完了（{@code ApplicationReadyEvent}）: 次の heartbeat から RUNNING を書く。 */
    void markRunning() {
        instanceStatus = "RUNNING";
    }

    /**
     * instance を STOPPED にする（業務の接続 pool を閉じた後にだけ呼ぶ。§6.11・I-5）。heartbeat の thread を先に止める。
     *
     * @return 書けたら true
     */
    public boolean writeStopped() {
        if (!registered || stopped) {
            return false;
        }
        stopped = true;
        heartbeatTicker.stop(THREAD_STOP_WAIT);
        try {
            boolean written = repository.markStopped(instanceId);
            log.info("backup の書込み静止の instance を STOPPED にしました（instance={}、更新={}）", instanceId, written);
            return written;
        } catch (RuntimeException failure) {
            log.warn("backup の書込み静止の instance を STOPPED にできませんでした（instance={}、{}）。停止済みの閾値（300 秒）で"
                    + "判定されます", instanceId, failure.getClass().getSimpleName());
            return false;
        }
    }

    /** 最後に破棄される: heartbeat の thread を止め、制御用・heartbeat 用の pool を閉じる。 */
    @Override
    public void destroy() {
        stopped = true;
        heartbeatTicker.stop(THREAD_STOP_WAIT);
        if (pools != null) {
            pools.close();
        }
    }

    private static String localHostName() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return name == null || name.isBlank() ? "unknown" : name.length() > 255 ? name.substring(0, 255) : name;
        } catch (Exception unavailable) {
            return "unknown";
        }
    }
}
