package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.AuditLog;
import com.ses.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.backup.quiesce.BackupQuiesceProperties;
import com.ses.backup.quiesce.BackupQuiesceTestSupport;
import com.ses.backup.quiesce.BackupWriteGate;
import com.ses.backup.quiesce.BackupWritePermit;
import com.ses.backup.quiesce.BackupWriteQuiescedException;
import com.ses.service.AuditLogService;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 監査ログサービスの単体テスト（P8フォローアップ・提案11）。
 * 記録の成功、記録失敗時に呼び出し元へ例外を伝播しないことを検証する。
 */
class AuditLogServiceImplTest {

    private AuditLogMapper auditLogMapper;
    private AuditLogServiceImpl service;

    @BeforeEach
    void setUp() {
        auditLogMapper = Mockito.mock(AuditLogMapper.class);
        service = new AuditLogServiceImpl(auditLogMapper);
    }

    @Test
    void record_正常時はinsertが呼ばれる() {
        service.record("admin", "POST", "/api/engineers", 200);
        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
    }

    @Test
    void record_DB書き込み失敗しても例外を伝播しない() {
        when(auditLogMapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> service.record("admin", "POST", "/api/engineers", 500));
    }

    @Test
    void recordRequired_DB書き込み失敗時は例外を伝播する() {
        when(auditLogMapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("DB down"));

        assertThrows(RuntimeException.class, () -> service.recordRequired(
                "BG-01", "AUTH", "/login", 200, "BREAK_GLASS_LOGIN_SUCCESS", true));
    }

    @Test
    void page_マッパーのselectPageへ委譲する() {
        when(auditLogMapper.selectPage(any(), any())).thenReturn(new Page<>());

        service.page(1, 10, "admin", "POST");

        verify(auditLogMapper, times(1)).selectPage(any(), any());
    }

    // ---------------------------------------------------------------- backup の書込み静止（RB-QUIESCE BWQ-005）

    private BackupWriteGate closedGate() {
        BackupWriteGate gate = BackupQuiesceTestSupport.openGate(false, () -> 0L, Clock.systemUTC());
        service.setBackupWriteGate(gate);
        return gate;
    }

    private ListAppender<ILoggingEvent> captureLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuditLogServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AuditLogServiceImpl.class)).detachAppender(appender);
    }

    @Test
    void record_静止中のbest_effortの監査はDBへ書かずWARNに内容を残す() {
        BackupWriteGate gate = closedGate();
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(5));
        ListAppender<ILoggingEvent> appender = captureLog();
        try {
            service.record("admin", "GET", "/api/files/a.pdf", 200, "FILE_DOWNLOAD", true);
        } finally {
            detach(appender);
        }

        verify(auditLogMapper, never()).insert(any(AuditLog.class));
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("errorCode=BACKUP_QUIESCE_AUDIT_DEFERRED", "user=admin",
                    "method=GET", "uri=/api/files/a.pdf", "status=200", "applicationCode=FILE_DOWNLOAD");
        });
    }

    @Test
    void record_閉じる前にpermitを取ったthreadは静止中も書く() {
        BackupWriteGate gate = closedGate();
        BackupWritePermit permit = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "POST /api/engineers");
        try {
            BackupQuiesceTestSupport.beginClosing(gate, 1);
            service.record("admin", "POST", "/api/engineers", 200, "ses-manager", true);
        } finally {
            permit.close();
        }

        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
    }

    @Test
    void recordRequired_静止中にpermitが無ければ書かずに静止の例外にする() {
        BackupWriteGate gate = closedGate();
        BackupQuiesceTestSupport.beginClosing(gate, 1);

        assertThrows(BackupWriteQuiescedException.class, () -> service.recordRequired(
                "BG-01", "AUTH", "/login", 200, "BREAK_GLASS_LOGIN_SUCCESS", true));
        verify(auditLogMapper, never()).insert(any(AuditLog.class));
    }

    @Test
    void recordCaptured_取っておいた時刻と主体とcorrelationIDをそのまま書く() {
        LocalDateTime capturedAt = LocalDateTime.now().minusMinutes(50);
        AuditLogService.CapturedContext context = new AuditLogService.CapturedContext(capturedAt, "SYSTEM",
                "SCHEDULER_POLL", null, "corr-logout-1", null, null, null, null, null, null, null);
        when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1);

        service.recordCaptured("admin", "AUTH", "/logout", 302, "LOGOUT", true, context);

        ArgumentCaptor<AuditLog> written = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(written.capture());
        assertThat(written.getValue().getCreatedAt()).isEqualTo(capturedAt);
        assertThat(written.getValue().getCorrelationId()).isEqualTo("corr-logout-1");
        assertThat(written.getValue().getActorType()).isEqualTo("SYSTEM");
        assertThat(written.getValue().getMethod()).isEqualTo("AUTH");
        assertThat(written.getValue().getApplicationCode()).isEqualTo("LOGOUT");
    }

    @Test
    void recordCaptured_静止の最長の保持時間と余裕より古い時刻と未来の時刻は書かずにWARNを残す() {
        BackupQuiesceProperties properties = new BackupQuiesceProperties();
        service.setBackupQuiesceProperties(properties);
        Duration allowed = properties.longestHold().plus(AuditLogServiceImpl.CAPTURED_MARGIN_BASE)
                .plus(properties.getMaxHoldCheckpoint());
        ListAppender<ILoggingEvent> appender = captureLog();
        try {
            for (LocalDateTime createdAt : new LocalDateTime[] {
                    LocalDateTime.now().minus(allowed).minusMinutes(1), LocalDateTime.now().plusMinutes(1), null}) {
                service.recordCaptured("admin", "AUTH", "/logout", 302, "LOGOUT", true,
                        new AuditLogService.CapturedContext(createdAt, "SYSTEM", "SCHEDULER_POLL", null, "c", null,
                                null, null, null, null, null, null));
            }
            when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1);
            service.recordCaptured("admin", "AUTH", "/logout", 302, "LOGOUT", true,
                    new AuditLogService.CapturedContext(LocalDateTime.now().minus(allowed).plusMinutes(1), "SYSTEM",
                            "SCHEDULER_POLL", null, "c", null, null, null, null, null, null, null));
        } finally {
            detach(appender);
        }

        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
        assertThat(appender.list).hasSize(3).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED",
                        "user=admin", "applicationCode=LOGOUT"));
    }

    @Test
    void recordCaptured_時計の補正で数秒先に見える時刻は書き5秒を超える未来は書かない() {
        // 2b review P3-7: logout の後に NTP が時計を少し戻しても監査を失わない（許す幅は 5 秒）
        assertThat(AuditLogServiceImpl.CAPTURED_FUTURE_TOLERANCE).isEqualTo(Duration.ofSeconds(5));
        when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(1);
        ListAppender<ILoggingEvent> appender = captureLog();
        try {
            service.recordCaptured("admin", "AUTH", "/logout", 302, "LOGOUT", true,
                    new AuditLogService.CapturedContext(LocalDateTime.now().plusSeconds(2), "SYSTEM", "SCHEDULER_POLL",
                            null, "c", null, null, null, null, null, null, null));
            service.recordCaptured("admin", "AUTH", "/logout", 302, "LOGOUT", true,
                    new AuditLogService.CapturedContext(LocalDateTime.now().plusSeconds(30), "SYSTEM", "SCHEDULER_POLL",
                            null, "c", null, null, null, null, null, null, null));
        } finally {
            detach(appender);
        }

        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED"));
    }

    @Test
    void recordCaptured_DBへ書けなければ例外を返し解除後の書込みに繰り返させる() {
        when(auditLogMapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("DB down"));

        assertThrows(RuntimeException.class, () -> service.recordCaptured("admin", "AUTH", "/logout", 302, "LOGOUT",
                true, AuditLogService.CapturedContext.capture()));
    }
}
