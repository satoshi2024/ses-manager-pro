package com.ses.backup.quiesce;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 信号表の SQL の実装（design.md §3・§5・§6.9）。制御用（状態機械）と heartbeat 用の2つの {@code JdbcTemplate} だけを使う。
 *
 * <p>時刻は全て {@code UTC_TIMESTAMP(6)} で書き・比べる（{@code NOW()}・{@code CURRENT_TIMESTAMP}・{@code LOCALTIMESTAMP}・JVM の
 * 時刻は使わない。§3.10）。最大保持時間と保存期間は検査済みの設定値を秒の整数として SQL に埋め込む（利用者の入力ではない）。
 * 状態の遷移は条件付きの文と影響した行数で確定する。
 */
final class JdbcBackupQuiesceSignalRepository implements BackupQuiesceSignalRepository {

    private static final String REQUEST_COLUMNS_TEMPLATE = "SELECT id, mode, job_kind, status, release_reason, protocol_version, "
            + "CASE WHEN lease_expires_at > UTC_TIMESTAMP(6) THEN 1 ELSE 0 END AS lease_valid, "
            + "CASE WHEN TIMESTAMPADD(SECOND, %1$s, requested_at) <= UTC_TIMESTAMP(6) THEN 1 ELSE 0 END AS max_hold_reached, "
            + "CASE WHEN lease_expires_at >= TIMESTAMPADD(SECOND, %1$s, requested_at) THEN 1 ELSE 0 END AS hold_capped, "
            + "LEAST(lease_expires_at, TIMESTAMPADD(SECOND, %1$s, requested_at)) AS hold_until, "
            + "TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), LEAST(lease_expires_at, TIMESTAMPADD(SECOND, %1$s, requested_at))) "
            + "AS hold_remaining_us, verify_requested_at FROM sys_backup_quiesce_request ";

    private final JdbcTemplate control;
    private final JdbcTemplate heartbeat;
    private final String requestColumns;

    JdbcBackupQuiesceSignalRepository(JdbcTemplate control, JdbcTemplate heartbeat, BackupQuiesceProperties properties) {
        this.control = Objects.requireNonNull(control, "control");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        String maxHoldSeconds = "CASE WHEN job_kind = 'FULL' THEN " + properties.getMaxHoldFull().getSeconds()
                + " ELSE " + properties.getMaxHoldCheckpoint().getSeconds() + " END";
        this.requestColumns = String.format(REQUEST_COLUMNS_TEMPLATE, maxHoldSeconds);
    }

    private static final RowMapper<RequestView> REQUEST = (rs, row) -> new RequestView(
            rs.getLong("id"), rs.getString("mode"), rs.getString("job_kind"), rs.getString("status"),
            rs.getString("release_reason"), rs.getInt("protocol_version"), rs.getInt("lease_valid") == 1,
            rs.getInt("max_hold_reached") == 1, rs.getInt("hold_capped") == 1, rs.getObject("hold_until", LocalDateTime.class),
            rs.getLong("hold_remaining_us"), rs.getObject("verify_requested_at", LocalDateTime.class));

    @Override
    public Registered registerInstance(InstanceRegistration registration) {
        return heartbeat.execute((ConnectionCallback<Registered>) connection -> {
            String user;
            long connectionId;
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(queryTimeoutSeconds());
                try (ResultSet result = statement.executeQuery("SELECT USER(), CONNECTION_ID()")) {
                    result.next();
                    user = result.getString(1);
                    connectionId = result.getLong(2);
                }
            }
            String dbClientHost = BackupQuiesceHosts.fromUserFunction(user);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO sys_backup_quiesce_instance "
                    + "(instance_id, host_name, db_client_host, db_connection_id, app_commit, protocol_version, "
                    + "poll_interval_ms, heartbeat_interval_ms, fence_after_ms, max_hold_checkpoint_ms, max_hold_full_ms, "
                    + "scheduling_enabled, status, gate_state, started_at, last_beat_at) VALUES "
                    + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTING', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
                insert.setQueryTimeout(queryTimeoutSeconds());
                insert.setString(1, registration.instanceId());
                insert.setString(2, registration.hostName());
                insert.setString(3, dbClientHost);
                insert.setLong(4, connectionId);
                insert.setString(5, registration.appCommit());
                insert.setInt(6, PROTOCOL_VERSION);
                insert.setLong(7, registration.pollIntervalMs());
                insert.setLong(8, registration.heartbeatIntervalMs());
                insert.setLong(9, registration.fenceAfterMs());
                insert.setLong(10, registration.maxHoldCheckpointMs());
                insert.setLong(11, registration.maxHoldFullMs());
                insert.setInt(12, registration.schedulingEnabled() ? 1 : 0);
                insert.setString(13, registration.gateState());
                insert.executeUpdate();
            }
            return new Registered(dbClientHost, connectionId);
        });
    }

    @Override
    public boolean heartbeat(String instanceId, String status, String gateState) {
        return heartbeat.update("UPDATE sys_backup_quiesce_instance SET last_beat_at = UTC_TIMESTAMP(6), status = ?, "
                + "gate_state = ? WHERE instance_id = ? AND status <> 'STOPPED'", status, gateState, instanceId) == 1;
    }

    @Override
    public boolean markStopped(String instanceId) {
        return heartbeat.update("UPDATE sys_backup_quiesce_instance SET status = 'STOPPED', gate_state = 'SHUTDOWN', "
                + "stopped_at = UTC_TIMESTAMP(6), last_beat_at = UTC_TIMESTAMP(6) "
                + "WHERE instance_id = ? AND status <> 'STOPPED'", instanceId) == 1;
    }

    @Override
    public Optional<RequestView> findActiveRequest() {
        return control.query(requestColumns + "WHERE status = 'REQUESTED' ORDER BY id DESC LIMIT 1", REQUEST)
                .stream().findFirst();
    }

    @Override
    public Optional<RequestView> findRequest(long requestId) {
        return control.query(requestColumns + "WHERE id = ?", REQUEST, requestId).stream().findFirst();
    }

    @Override
    public Observation observeAppHosts() {
        List<String> hosts = new ArrayList<>();
        LocalDateTime[] observedAt = new LocalDateTime[1];
        control.query("SELECT t.observed_at, p.HOST FROM (SELECT UTC_TIMESTAMP(6) AS observed_at) t "
                + "LEFT JOIN information_schema.PROCESSLIST p ON p.USER = SUBSTRING_INDEX(USER(), '@', 1)", (RowCallbackHandler) rs -> {
                    observedAt[0] = rs.getObject(1, LocalDateTime.class);
                    String host = rs.getString(2);
                    if (host != null) {
                        hosts.add(host);
                    }
                });
        if (observedAt[0] == null) {
            throw new IllegalStateException("接続元の観測の時刻を読めませんでした");
        }
        return new Observation(BackupQuiesceHosts.aggregate(hosts), observedAt[0]);
    }

    @Override
    public void insertQuiescedAck(long requestId, String instanceId, String schedulerState, int drainMillis,
                                  LocalDateTime holdUntil, Observation observation) {
        control.update("INSERT INTO sys_backup_quiesce_ack (request_id, instance_id, state, scheduler_state, drain_millis, "
                        + "hold_until, observed_app_hosts, observed_at, acked_at) "
                        + "VALUES (?, ?, 'QUIESCED', ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                requestId, instanceId, schedulerState, drainMillis, holdUntil, observation.hosts(), observation.observedAt());
    }

    @Override
    public void insertRefusedAck(long requestId, String instanceId, String schedulerState, int drainMillis,
                                 String refuseReason, Observation observation) {
        if (observation == null) {
            control.update("INSERT INTO sys_backup_quiesce_ack (request_id, instance_id, state, scheduler_state, "
                            + "drain_millis, observed_app_hosts, observed_at, refuse_reason, acked_at) "
                            + "VALUES (?, ?, 'REFUSED', ?, ?, '', UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6))",
                    requestId, instanceId, schedulerState, drainMillis, refuseReason);
        } else {
            control.update("INSERT INTO sys_backup_quiesce_ack (request_id, instance_id, state, scheduler_state, "
                            + "drain_millis, observed_app_hosts, observed_at, refuse_reason, acked_at) "
                            + "VALUES (?, ?, 'REFUSED', ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                    requestId, instanceId, schedulerState, drainMillis, observation.hosts(), observation.observedAt(),
                    refuseReason);
        }
    }

    @Override
    public Optional<AckState> findAckState(long requestId, String instanceId) {
        return control.query("SELECT state FROM sys_backup_quiesce_ack WHERE request_id = ? AND instance_id = ?",
                (rs, row) -> AckState.valueOf(rs.getString(1)), requestId, instanceId).stream().findFirst();
    }

    @Override
    public int updateHoldUntil(long requestId, String instanceId, LocalDateTime holdUntil) {
        return control.update("UPDATE sys_backup_quiesce_ack SET hold_until = ? WHERE request_id = ? AND instance_id = ? "
                + "AND state = 'QUIESCED' AND hold_until < ?", holdUntil, requestId, instanceId, holdUntil);
    }

    @Override
    public int updateObservation(long requestId, String instanceId, Observation observation) {
        return control.update("UPDATE sys_backup_quiesce_ack SET observed_app_hosts = ?, observed_at = ? "
                        + "WHERE request_id = ? AND instance_id = ? AND state = 'QUIESCED'",
                observation.hosts(), observation.observedAt(), requestId, instanceId);
    }

    @Override
    public int markResumed(long requestId, String instanceId, String resumeReason) {
        return control.update("UPDATE sys_backup_quiesce_ack SET state = 'RESUMED', resumed_at = UTC_TIMESTAMP(6), "
                + "resume_reason = ? WHERE request_id = ? AND instance_id = ? AND state = 'QUIESCED'",
                resumeReason, requestId, instanceId);
    }

    @Override
    public int purgeRequests(Duration retention) {
        return control.update("DELETE FROM sys_backup_quiesce_request WHERE status IN ('RELEASED', 'ABORTED', 'EXPIRED') "
                + "AND requested_at < TIMESTAMPADD(SECOND, " + (-retention.getSeconds()) + ", UTC_TIMESTAMP(6))");
    }

    @Override
    public int purgeInstances(Duration retention, String ownInstanceId) {
        return control.update("DELETE FROM sys_backup_quiesce_instance WHERE last_beat_at < TIMESTAMPADD(SECOND, "
                + (-retention.getSeconds()) + ", UTC_TIMESTAMP(6)) AND instance_id <> ?", ownInstanceId);
    }

    private static int queryTimeoutSeconds() {
        return (int) BackupQuiesceProperties.CONTROL_QUERY_TIMEOUT.getSeconds();
    }
}
