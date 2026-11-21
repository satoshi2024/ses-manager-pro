package com.ses.backup.quiesce;

import com.ses.common.util.CorrelationContext;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.AuditLogService;
import com.ses.service.security.PersistentSessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RB-QUIESCE BWQ-005（U-8。design.md §6.13・D3-P3-5）: 静止中の logout は session の行の失効と LOGOUT の監査を logout の時点の
 * 値で取っておいて解除の後に書き、解除後の書込みの途中で gate がまた閉じれば捨てずに次の解除で書く。
 */
class BackupQuiesceLogoutDeferralTest {

    private final PersistentSessionService sessions = mock(PersistentSessionService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private BackupWriteGate gate;
    private BackupQuiesceLogoutDeferral deferral;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        gate = BackupQuiesceTestSupport.openGate(false, () -> 0L, Clock.systemUTC());
        BackupQuiesceMetrics metrics = new BackupQuiesceMetrics(gate);
        metrics.bindTo(registry);
        deferral = new BackupQuiesceLogoutDeferral(sessions, audit, () -> gate, () -> metrics);
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("admin");
        user.setStatus(1);
        authentication = new UsernamePasswordAuthenticationToken(new LoginUser(user, List.of()), null, List.of());
    }

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    private PersistentSessionService.CapturedRevocation captured(LocalDateTime at) {
        return new PersistentSessionService.CapturedRevocation("tenant-a", "hash-0123456789", 7L, at, "LOGOUT");
    }

    @Test
    void 開いている間は従来どおり直ちに失効と監査を書く() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");

        deferral.revokeOnLogout(request, authentication);
        deferral.recordLogoutAudit(authentication);

        verify(sessions).revokeCurrent(request, authentication, "LOGOUT");
        verify(audit).record("admin", "AUTH", "/logout", 302, "LOGOUT", true);
        verify(sessions, never()).captureCurrent(any(), any(), any());
        assertThat(gate.deferredWrites().pending()).isZero();
    }

    @Test
    void 閉じる前にpermitを取ったlogoutは静止中も直ちに書く() {
        BackupWritePermit permit = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "POST /logout");
        try {
            BackupQuiesceTestSupport.beginClosing(gate, 1);
            deferral.revokeOnLogout(new MockHttpServletRequest("POST", "/logout"), authentication);
            deferral.recordLogoutAudit(authentication);
        } finally {
            permit.close();
        }

        verify(sessions).revokeCurrent(any(), eq(authentication), eq("LOGOUT"));
        verify(audit).record("admin", "AUTH", "/logout", 302, "LOGOUT", true);
        assertThat(gate.deferredWrites().pending()).isZero();
    }

    @Test
    void 静止中のlogoutは失効と監査をlogoutの時点の値で後へ回し解除の後に書く() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        LocalDateTime logoutAt = LocalDateTime.now();
        when(sessions.captureCurrent(request, authentication, "LOGOUT")).thenReturn(captured(logoutAt));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(5));
        CorrelationContext.put(CorrelationContext.CORRELATION_ID, "corr-at-logout-0001");

        deferral.revokeOnLogout(request, authentication);
        deferral.recordLogoutAudit(authentication);

        verify(sessions, never()).revokeCurrent(any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), anyInt(), any(), anyBoolean());
        assertThat(gate.deferredWrites().pending()).isEqualTo(2);
        assertThat(registry.get(BackupQuiesceMetrics.DEFERRED).tag("kind", "logout").counter().count()).isEqualTo(2.0);

        // 解除の後（executor の thread。logout の時の文脈は無い）
        CorrelationContext.clear();
        BackupQuiesceTestSupport.reopen(gate, 1);
        assertThat(gate.deferredWrites().flush(gate)).isEqualTo(2);

        verify(sessions).revokeCaptured(captured(logoutAt));
        ArgumentCaptor<AuditLogService.CapturedContext> context = ArgumentCaptor.forClass(AuditLogService.CapturedContext.class);
        verify(audit).recordCaptured(eq("admin"), eq("AUTH"), eq("/logout"), eq(302), eq("LOGOUT"), eq(true),
                context.capture());
        assertThat(context.getValue().correlationId()).isEqualTo("corr-at-logout-0001");
        assertThat(context.getValue().createdAt()).isBetween(logoutAt, LocalDateTime.now());
        assertThat(gate.deferredWrites().pending()).isZero();
    }

    @Test
    void 解除後の書込みの途中でgateがまた閉じると残りは捨てずに次の解除で書く() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        when(sessions.captureCurrent(request, authentication, "LOGOUT")).thenReturn(captured(LocalDateTime.now()));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(5));
        deferral.revokeOnLogout(request, authentication);
        deferral.recordLogoutAudit(authentication);
        AtomicInteger revoked = new AtomicInteger();
        doAnswer(invocation -> {
            revoked.incrementAndGet();
            // 1 件目を書いている間に次の checkpoint の静止が始まる
            BackupQuiesceTestSupport.beginClosing(gate, 2);
            return null;
        }).when(sessions).revokeCaptured(any());

        BackupQuiesceTestSupport.reopen(gate, 1);
        assertThat(gate.deferredWrites().flush(gate)).isEqualTo(1);
        verify(audit, never()).recordCaptured(anyString(), anyString(), anyString(), anyInt(), anyString(), anyBoolean(),
                any());
        assertThat(gate.deferredWrites().pending()).isEqualTo(1);
        assertThat(gate.deferredWrites().droppedCount()).isZero();

        BackupQuiesceTestSupport.reopen(gate, 2);
        assertThat(gate.deferredWrites().flush(gate)).isEqualTo(1);
        verify(audit, times(1)).recordCaptured(eq("admin"), eq("AUTH"), eq("/logout"), eq(302), eq("LOGOUT"), eq(true),
                any());
        assertThat(revoked).hasValue(1);
        assertThat(gate.deferredWrites().pending()).isZero();
    }

    @Test
    void 失効させる行が無いlogoutは失効を後へ回さず監査だけを回す() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        when(sessions.captureCurrent(request, null, "LOGOUT")).thenReturn(null);
        BackupQuiesceTestSupport.beginClosing(gate, 1);

        deferral.revokeOnLogout(request, null);
        deferral.recordLogoutAudit(null);

        assertThat(gate.deferredWrites().pending()).isEqualTo(1);
        verify(sessions, never()).revokeCurrent(any(), any(), any());
    }
}
