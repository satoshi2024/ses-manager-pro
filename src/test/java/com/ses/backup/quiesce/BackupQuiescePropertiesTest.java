package com.ses.backup.quiesce;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-002: {@code app.backup-quiesce.*} の既定値が利用者の決定（U-1・U-3・U-12）の値で、範囲外・順序の誤りは
 * bind で失敗し起動しないこと（design.md §12.1）。{@code application.yml} の既定と環境変数の名前も固定する。
 */
class BackupQuiescePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BackupQuiesceProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void 既定値は利用者の決定の値である() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            BackupQuiesceProperties properties = context.getBean(BackupQuiesceProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getDisabledTicket()).isEmpty();
            assertThat(properties.getPollInterval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getFenceAfter()).isEqualTo(Duration.ofSeconds(20));
            // U-3: 排水の上限 checkpoint 30 秒・full 600 秒
            assertThat(properties.getDrainTimeoutCheckpoint()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getDrainTimeoutFull()).isEqualTo(Duration.ofSeconds(600));
            // U-1: 最大保持時間 checkpoint 300 秒・full 60 分
            assertThat(properties.getMaxHoldCheckpoint()).isEqualTo(Duration.ofSeconds(300));
            assertThat(properties.getMaxHoldFull()).isEqualTo(Duration.ofMinutes(60));
            assertThat(properties.getStartupWaitMax()).isEqualTo(Duration.ofSeconds(60));
            // U-12: 保存期間 30 日
            assertThat(properties.getRetention()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.getDeferredWritesMax()).isEqualTo(10_000);
            assertThat(properties.maxHold(true)).isEqualTo(Duration.ofMinutes(60));
            assertThat(properties.drainTimeout(false)).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void 制御用の接続のtimeoutはfence_afterとheartbeatの差より短い() {
        Duration margin = new BackupQuiesceProperties().getFenceAfter().minus(new BackupQuiesceProperties()
                .getHeartbeatInterval());
        for (Duration fixed : new Duration[]{BackupQuiesceProperties.CONTROL_LOCK_WAIT_TIMEOUT,
                BackupQuiesceProperties.CONTROL_QUERY_TIMEOUT, BackupQuiesceProperties.CONTROL_SOCKET_TIMEOUT,
                BackupQuiesceProperties.CONTROL_CONNECT_TIMEOUT, BackupQuiesceProperties.CONTROL_POOL_CONNECTION_TIMEOUT,
                BackupQuiesceProperties.CONTROL_POOL_VALIDATION_TIMEOUT}) {
            assertThat(fixed).isLessThanOrEqualTo(BackupQuiesceProperties.CONTROL_STATEMENT_BLOCKING_MAX)
                    .isLessThan(margin);
        }
        assertThat(BackupQuiesceProperties.CONTROL_POOL_CONNECTION_TIMEOUT)
                .as("Hikari は connectionTimeout > validationTimeout を要する")
                .isGreaterThan(BackupQuiesceProperties.CONTROL_POOL_VALIDATION_TIMEOUT);
    }

    @Test
    void 範囲内の上限と下限はbindできる() {
        runner.withPropertyValues(
                        "app.backup-quiesce.enabled=false",
                        "app.backup-quiesce.disabled-ticket= CHG-123 ",
                        "app.backup-quiesce.poll-interval=200ms",
                        "app.backup-quiesce.heartbeat-interval=2s",
                        "app.backup-quiesce.fence-after=30s",
                        "app.backup-quiesce.max-hold-checkpoint=900s",
                        "app.backup-quiesce.drain-timeout-checkpoint=899s",
                        "app.backup-quiesce.max-hold-full=4h",
                        "app.backup-quiesce.drain-timeout-full=1s",
                        "app.backup-quiesce.startup-wait-max=10s",
                        "app.backup-quiesce.retention=365d",
                        "app.backup-quiesce.deferred-writes-max=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BackupQuiesceProperties properties = context.getBean(BackupQuiesceProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getDisabledTicket()).isEqualTo("CHG-123");
                    assertThat(properties.getMaxHoldFull()).isEqualTo(Duration.ofHours(4));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 順序: poll < heartbeat < fence-after ≤ 30 秒、poll は tick（200ms）以上
            "app.backup-quiesce.poll-interval=5s",
            "app.backup-quiesce.poll-interval=100ms",
            "app.backup-quiesce.heartbeat-interval=20s",
            "app.backup-quiesce.fence-after=31s",
            // 制御用の接続の1つの文の上限（接続の取得 3 秒＋socket 10 秒 = 13 秒）< fence-after − heartbeat-interval（2a review P3-9）
            "app.backup-quiesce.fence-after=15s",
            "app.backup-quiesce.fence-after=18s",
            // 範囲
            "app.backup-quiesce.max-hold-checkpoint=29s",
            "app.backup-quiesce.max-hold-checkpoint=901s",
            "app.backup-quiesce.max-hold-full=59s",
            "app.backup-quiesce.max-hold-full=241m",
            "app.backup-quiesce.startup-wait-max=9s",
            "app.backup-quiesce.startup-wait-max=121s",
            "app.backup-quiesce.retention=23h",
            "app.backup-quiesce.retention=366d",
            "app.backup-quiesce.deferred-writes-max=0",
            "app.backup-quiesce.deferred-writes-max=10001",
            // 排水の上限 < 最大保持時間、正の値
            "app.backup-quiesce.drain-timeout-checkpoint=300s",
            "app.backup-quiesce.drain-timeout-full=3600s",
            "app.backup-quiesce.drain-timeout-checkpoint=0s",
            "app.backup-quiesce.drain-timeout-full=-1s"})
    void 範囲外と順序の誤りはbindで失敗し起動しない(String property) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).as(property).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("app.backup-quiesce");
        });
    }

    @Test
    void application_ymlは環境変数で上書きでき既定は決定の値() throws Exception {
        String yml = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("enabled: ${BACKUP_QUIESCE_ENABLED:true}")
                .contains("disabled-ticket: ${BACKUP_QUIESCE_DISABLED_TICKET:}")
                .contains("poll-interval: ${BACKUP_QUIESCE_POLL_INTERVAL:1s}")
                .contains("heartbeat-interval: ${BACKUP_QUIESCE_HEARTBEAT_INTERVAL:5s}")
                .contains("fence-after: ${BACKUP_QUIESCE_FENCE_AFTER:20s}")
                .contains("drain-timeout-checkpoint: ${BACKUP_QUIESCE_DRAIN_TIMEOUT_CHECKPOINT:30s}")
                .contains("drain-timeout-full: ${BACKUP_QUIESCE_DRAIN_TIMEOUT_FULL:600s}")
                .contains("max-hold-checkpoint: ${BACKUP_QUIESCE_MAX_HOLD_CHECKPOINT:300s}")
                .contains("max-hold-full: ${BACKUP_QUIESCE_MAX_HOLD_FULL:3600s}")
                .contains("startup-wait-max: ${BACKUP_QUIESCE_STARTUP_WAIT_MAX:60s}")
                .contains("retention: ${BACKUP_QUIESCE_RETENTION:30d}");
        String test = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/application-test.yml"));
        assertThat(test).as("test profile では静止応答を動かさない").containsPattern("backup-quiesce:\\s*\\n\\s*enabled: false");
    }
}
