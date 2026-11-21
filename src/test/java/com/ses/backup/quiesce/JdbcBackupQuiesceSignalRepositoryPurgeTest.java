package com.ses.backup.quiesce;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2a review P3-7（design.md §3.9・§5.3・§9.4）: 信号表の保存期間の削除は、停止口で動いていた行（DISABLED で STOPPED でない）を
 * 古さで消さない。DBA が確かめて手で STOPPED にするまで backup を失敗させ続ける行のため。専用の H2（V202 の H2 の変種）で、
 * 実の SQL を流して確かめる。
 */
class JdbcBackupQuiesceSignalRepositoryPurgeTest {

    @Test
    void 保存期間を過ぎたinstanceを消し停止口で動いていた行と自分の行は残す() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:bq_purge_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:sql/schema-backup-quiesce-h2.sql'",
                "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcBackupQuiesceSignalRepository repository = new JdbcBackupQuiesceSignalRepository(jdbc, jdbc,
                new BackupQuiesceProperties());
        insert(jdbc, "old-running", "RUNNING", "OPEN", "2020-01-01 00:00:00");
        insert(jdbc, "old-stopped", "STOPPED", "SHUTDOWN", "2020-01-01 00:00:00");
        insert(jdbc, "old-disabled-stopped", "STOPPED", "DISABLED", "2020-01-01 00:00:00");
        insert(jdbc, "old-disabled-live", "RUNNING", "DISABLED", "2020-01-01 00:00:00");
        insert(jdbc, "own-old", "RUNNING", "OPEN", "2020-01-01 00:00:00");
        jdbc.update("INSERT INTO sys_backup_quiesce_instance (instance_id, host_name, db_client_host, db_connection_id, "
                + "app_commit, protocol_version, poll_interval_ms, heartbeat_interval_ms, fence_after_ms, "
                + "max_hold_checkpoint_ms, max_hold_full_ms, scheduling_enabled, status, gate_state, started_at, "
                + "last_beat_at) VALUES ('" + id("recent") + "', 'h', 'c', 1, 'x', 1, 1000, 5000, 20000, 300000, 3600000, 1, "
                + "'RUNNING', 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");

        int purged = repository.purgeInstances(Duration.ofDays(30), id("own-old"));

        assertThat(purged).isEqualTo(3);
        List<String> remaining = jdbc.queryForList(
                "SELECT instance_id FROM sys_backup_quiesce_instance ORDER BY instance_id", String.class);
        assertThat(remaining).containsExactlyInAnyOrder(id("old-disabled-live"), id("own-old"), id("recent"));
    }

    private static void insert(JdbcTemplate jdbc, String id, String status, String gateState, String lastBeat) {
        jdbc.update("INSERT INTO sys_backup_quiesce_instance (instance_id, host_name, db_client_host, db_connection_id, "
                        + "app_commit, protocol_version, poll_interval_ms, heartbeat_interval_ms, fence_after_ms, "
                        + "max_hold_checkpoint_ms, max_hold_full_ms, scheduling_enabled, status, gate_state, started_at, "
                        + "last_beat_at) VALUES (?, 'h', 'c', 1, 'x', 1, 1000, 5000, 20000, 300000, 3600000, 1, ?, ?, ?, ?)",
                id(id), status, gateState, lastBeat, lastBeat);
    }

    /** instance_id は CHAR(36)（UUID の形）。名前から決まる UUID にする。 */
    private static String id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
