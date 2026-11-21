package com.ses.backup.quiesce;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * backup の書込み静止の設定（{@code app.backup-quiesce.*}、環境変数 {@code BACKUP_QUIESCE_*}。design.md §12.1）。
 *
 * <p>値の範囲と相互の関係を全 profile で bind の時に検査し、範囲外は起動を中止する（prod では fail-fast の1つ。
 * deploy-runbook §1.0）。既定値は利用者の決定による: 最大保持時間 checkpoint 300 秒・full 60 分（U-1）、
 * 排水の上限 checkpoint 30 秒・full 600 秒（U-3）、信号表の保存期間 30 日（U-12）。
 *
 * <p>制御用・heartbeat 用の接続の timeout は設定にせず固定値とする（§5.2）。1つの文が静止応答の thread を塞ぐ時間の上限
 * （{@link #CONTROL_STATEMENT_BLOCKING_MAX}）は {@code fence-after − heartbeat-interval} より短くなければならない。
 */
@Validated
@ConfigurationProperties(prefix = "app.backup-quiesce")
public class BackupQuiesceProperties {

    /** 状態機械の tick（1 tick に短い文を高々1つ実行する）。要求の読取り間隔はこれより短くできない。 */
    public static final Duration TICK = Duration.ofMillis(200);
    /** 制御用・heartbeat 用の接続の {@code innodb_lock_wait_timeout}。 */
    public static final Duration CONTROL_LOCK_WAIT_TIMEOUT = Duration.ofSeconds(5);
    /** 制御用・heartbeat 用の {@code JdbcTemplate} の query timeout。 */
    public static final Duration CONTROL_QUERY_TIMEOUT = Duration.ofSeconds(5);
    /** Connector/J の {@code socketTimeout}。 */
    public static final Duration CONTROL_SOCKET_TIMEOUT = Duration.ofSeconds(10);
    /** Connector/J の {@code connectTimeout}。 */
    public static final Duration CONTROL_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Hikari の {@code connectionTimeout}（pool の接続待ち。D3-P3-3）。 */
    public static final Duration CONTROL_POOL_CONNECTION_TIMEOUT = Duration.ofSeconds(3);
    /** Hikari の {@code validationTimeout}。 */
    public static final Duration CONTROL_POOL_VALIDATION_TIMEOUT = Duration.ofSeconds(2);
    /** 1つの文が thread を塞ぐ時間の上限（上の固定値の最大。socket の読み待ち）。 */
    public static final Duration CONTROL_STATEMENT_BLOCKING_MAX = CONTROL_SOCKET_TIMEOUT;

    private boolean enabled = true;
    /** prod で {@code enabled=false} を許す変更 ticket（U-7。形式の検査は prod の起動時検査が行う）。 */
    private String disabledTicket = "";
    @NotNull
    private Duration pollInterval = Duration.ofSeconds(1);
    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    @NotNull
    private Duration fenceAfter = Duration.ofSeconds(20);
    @NotNull
    private Duration drainTimeoutCheckpoint = Duration.ofSeconds(30);
    @NotNull
    private Duration drainTimeoutFull = Duration.ofSeconds(600);
    @NotNull
    private Duration maxHoldCheckpoint = Duration.ofSeconds(300);
    @NotNull
    private Duration maxHoldFull = Duration.ofSeconds(3600);
    @NotNull
    private Duration startupWaitMax = Duration.ofSeconds(60);
    @NotNull
    private Duration retention = Duration.ofDays(30);
    @Min(1)
    @Max(10000)
    private int deferredWritesMax = 10000;

    // ---------------------------------------------------------------- 相互の関係（§12.1）

    @AssertTrue(message = "poll-interval は 200ms 以上で heartbeat-interval より短く、heartbeat-interval は fence-after より短く、"
            + "fence-after は 30 秒以下にしてください")
    public boolean isIntervalOrderValid() {
        if (pollInterval == null || heartbeatInterval == null || fenceAfter == null) {
            return true;
        }
        return pollInterval.compareTo(TICK) >= 0
                && pollInterval.compareTo(heartbeatInterval) < 0
                && heartbeatInterval.compareTo(fenceAfter) < 0
                && fenceAfter.compareTo(Duration.ofSeconds(30)) <= 0;
    }

    @AssertTrue(message = "制御用の接続の timeout（10 秒）が fence-after − heartbeat-interval より短くなるようにしてください")
    public boolean isControlTimeoutWithinFence() {
        if (heartbeatInterval == null || fenceAfter == null) {
            return true;
        }
        return CONTROL_STATEMENT_BLOCKING_MAX.compareTo(fenceAfter.minus(heartbeatInterval)) < 0;
    }

    @AssertTrue(message = "max-hold-checkpoint は 30 秒〜900 秒、drain-timeout-checkpoint は正の値で max-hold-checkpoint より"
            + "短くしてください")
    public boolean isCheckpointHoldValid() {
        if (maxHoldCheckpoint == null || drainTimeoutCheckpoint == null) {
            return true;
        }
        return within(maxHoldCheckpoint, Duration.ofSeconds(30), Duration.ofSeconds(900))
                && !drainTimeoutCheckpoint.isNegative() && !drainTimeoutCheckpoint.isZero()
                && drainTimeoutCheckpoint.compareTo(maxHoldCheckpoint) < 0;
    }

    @AssertTrue(message = "max-hold-full は 60 秒〜4 時間、drain-timeout-full は正の値で max-hold-full より短くしてください")
    public boolean isFullHoldValid() {
        if (maxHoldFull == null || drainTimeoutFull == null) {
            return true;
        }
        return within(maxHoldFull, Duration.ofSeconds(60), Duration.ofHours(4))
                && !drainTimeoutFull.isNegative() && !drainTimeoutFull.isZero()
                && drainTimeoutFull.compareTo(maxHoldFull) < 0;
    }

    @AssertTrue(message = "startup-wait-max は 10 秒〜120 秒にしてください")
    public boolean isStartupWaitValid() {
        return startupWaitMax == null || within(startupWaitMax, Duration.ofSeconds(10), Duration.ofSeconds(120));
    }

    @AssertTrue(message = "retention は 1 日〜365 日にしてください")
    public boolean isRetentionValid() {
        return retention == null || within(retention, Duration.ofDays(1), Duration.ofDays(365));
    }

    private static boolean within(Duration value, Duration min, Duration max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    /** job の種類の最大保持時間。 */
    public Duration maxHold(boolean fullBackup) {
        return fullBackup ? maxHoldFull : maxHoldCheckpoint;
    }

    /** job の種類の排水の上限。 */
    public Duration drainTimeout(boolean fullBackup) {
        return fullBackup ? drainTimeoutFull : drainTimeoutCheckpoint;
    }

    // ---------------------------------------------------------------- accessor

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDisabledTicket() {
        return disabledTicket;
    }

    public void setDisabledTicket(String disabledTicket) {
        this.disabledTicket = disabledTicket == null ? "" : disabledTicket.trim();
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getFenceAfter() {
        return fenceAfter;
    }

    public void setFenceAfter(Duration fenceAfter) {
        this.fenceAfter = fenceAfter;
    }

    public Duration getDrainTimeoutCheckpoint() {
        return drainTimeoutCheckpoint;
    }

    public void setDrainTimeoutCheckpoint(Duration drainTimeoutCheckpoint) {
        this.drainTimeoutCheckpoint = drainTimeoutCheckpoint;
    }

    public Duration getDrainTimeoutFull() {
        return drainTimeoutFull;
    }

    public void setDrainTimeoutFull(Duration drainTimeoutFull) {
        this.drainTimeoutFull = drainTimeoutFull;
    }

    public Duration getMaxHoldCheckpoint() {
        return maxHoldCheckpoint;
    }

    public void setMaxHoldCheckpoint(Duration maxHoldCheckpoint) {
        this.maxHoldCheckpoint = maxHoldCheckpoint;
    }

    public Duration getMaxHoldFull() {
        return maxHoldFull;
    }

    public void setMaxHoldFull(Duration maxHoldFull) {
        this.maxHoldFull = maxHoldFull;
    }

    public Duration getStartupWaitMax() {
        return startupWaitMax;
    }

    public void setStartupWaitMax(Duration startupWaitMax) {
        this.startupWaitMax = startupWaitMax;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public int getDeferredWritesMax() {
        return deferredWritesMax;
    }

    public void setDeferredWritesMax(int deferredWritesMax) {
        this.deferredWritesMax = deferredWritesMax;
    }
}
