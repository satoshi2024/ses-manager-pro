package com.ses.service.security;

import com.ses.backup.quiesce.BackupQuiesceProperties;
import com.ses.backup.quiesce.BackupQuiesceTestSupport;
import com.ses.backup.quiesce.BackupWriteGate;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.PersistentSessionProperties;
import com.ses.entity.SysUser;
import com.ses.entity.UserSession;
import com.ses.mapper.UserSessionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.impl.PersistentSessionServiceImpl;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistentSessionServiceImplTest {

    @Mock
    private UserSessionMapper userSessionMapper;
    @Mock
    private SysUserMapper sysUserMapper;

    private PersistentSessionServiceImpl service;
    private UsernamePasswordAuthenticationToken authentication;
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        PersistentSessionProperties properties = new PersistentSessionProperties();
        properties.setHashKey("test-session-key");
        properties.setMaxConcurrentSessions(2);
        properties.setIdleTimeoutMinutes(30);
        properties.setMaxLifetimeHours(12);
        OidcSecurityProperties oidcProperties = new OidcSecurityProperties();
        oidcProperties.setTenantId("tenant-a");
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("admin");
        user.setStatus(1);
        org.mockito.Mockito.lenient().when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(user);
        service = new PersistentSessionServiceImpl(userSessionMapper, sysUserMapper, properties, oidcProperties, clock);

        authentication = new UsernamePasswordAuthenticationToken(
                new LoginUser(user, List.of()), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sessionIDを平文保存せず登録属性を設定する() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userSessionMapper.selectBySessionHash(anyString(), anyString())).thenReturn(null);
        when(userSessionMapper.selectActiveByUser(anyString(), eq(7L), any())).thenReturn(List.of());

        service.register(request, authentication);

        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionMapper).insert(captor.capture());
        verify(sysUserMapper).selectByIdForUpdate(7L);
        HttpSession session = request.getSession(false);
        assertNotEquals(session.getId(), captor.getValue().getSessionIdHash());
        assertTrue(Boolean.TRUE.equals(session.getAttribute(PersistentSessionService.TRACKED_ATTRIBUTE)));
        assertNotEquals(null, session.getAttribute(PersistentSessionService.SESSION_HASH_ATTRIBUTE));
    }

    @Test
    void 永続sessionが期限切れならfailClosedする() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession(true);
        session.setAttribute(PersistentSessionService.TRACKED_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(PersistentSessionService.SESSION_HASH_ATTRIBUTE, "hashed-session");
        UserSession expired = new UserSession();
        expired.setId(10L);
        expired.setUserId(7L);
        expired.setTenantId("tenant-a");
        expired.setSessionIdHash("hashed-session");
        expired.setIdleExpiresAt(LocalDateTime.of(2025, 12, 31, 23, 59));
        expired.setExpiresAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        when(userSessionMapper.selectBySessionHash("tenant-a", "hashed-session")).thenReturn(expired);

        assertFalse(service.validateAndTouch(request, authentication));
    }

    @Test
    void 同時session上限を超えた場合は最古を失効する() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UserSession oldest = active(1L, LocalDateTime.of(2025, 12, 1, 0, 0));
        UserSession newest = active(2L, LocalDateTime.of(2025, 12, 31, 0, 0));
        when(userSessionMapper.selectBySessionHash(anyString(), anyString())).thenReturn(null);
        when(userSessionMapper.selectActiveByUser(anyString(), eq(7L), any()))
                .thenReturn(List.of(newest, oldest));

        service.register(request, authentication);

        verify(userSessionMapper).revoke(1L, LocalDateTime.of(2026, 1, 1, 0, 0), "MAX_CONCURRENT");
    }

    @Test
    void 無効ユーザーには永続sessionを発行しない() {
        SysUser disabled = new SysUser();
        disabled.setId(7L);
        disabled.setStatus(0);
        when(sysUserMapper.selectByIdForUpdate(7L)).thenReturn(disabled);

        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.register(new MockHttpServletRequest(), authentication));

        verify(userSessionMapper, never()).insert(any(UserSession.class));
    }

    @Test
    void logout時に現在の永続session行を失効する() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession httpSession = request.getSession(true);
        httpSession.setAttribute(PersistentSessionService.SESSION_HASH_ATTRIBUTE, "hashed-session");
        UserSession current = active(10L, LocalDateTime.of(2025, 12, 31, 0, 0));
        current.setSessionIdHash("hashed-session");
        when(userSessionMapper.selectBySessionHash("tenant-a", "hashed-session")).thenReturn(current);

        service.revokeCurrent(request, authentication, "LOGOUT");

        verify(userSessionMapper).revoke(10L, LocalDateTime.of(2026, 1, 1, 0, 0), "LOGOUT");
    }

    private UserSession active(Long id, LocalDateTime issuedAt) {
        UserSession session = new UserSession();
        session.setId(id);
        session.setUserId(7L);
        session.setTenantId("tenant-a");
        session.setSessionIdHash("session-" + id);
        session.setIssuedAt(issuedAt);
        session.setIdleExpiresAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        session.setExpiresAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        return session;
    }

    // ---------------------------------------------------------------- backup の書込み静止の間の idle（RB-QUIESCE U-14）

    /** 試験が進める時計（service と gate の両方に渡す）。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(LocalDateTime utc) {
            now = utc.toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 9, 0);

    private final MutableClock quiesceClock = new MutableClock(T0.toInstant(ZoneOffset.UTC));
    private PersistentSessionServiceImpl quiesceService;
    private BackupWriteGate gate;

    private void setUpQuiesce() {
        PersistentSessionProperties properties = new PersistentSessionProperties();
        properties.setHashKey("test-session-key");
        properties.setIdleTimeoutMinutes(30);
        properties.setMaxLifetimeHours(12);
        OidcSecurityProperties oidcProperties = new OidcSecurityProperties();
        oidcProperties.setTenantId("tenant-a");
        quiesceService = new PersistentSessionServiceImpl(userSessionMapper, sysUserMapper, properties, oidcProperties,
                quiesceClock);
        gate = BackupQuiesceTestSupport.openGate(false, () -> 0L, quiesceClock);
        quiesceService.setBackupWriteGate(gate);
        BackupQuiesceProperties quiesce = new BackupQuiesceProperties();
        quiesce.setMaxHoldFull(Duration.ofMinutes(60));
        quiesceService.setBackupQuiesceProperties(quiesce);
    }

    /** 追跡している session の要求。holder[0] が空なら新しい session を作って入れ、あれば同じ session を使う。 */
    private MockHttpServletRequest trackedRequest(HttpSession[] holder) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (holder[0] == null) {
            HttpSession session = request.getSession(true);
            session.setAttribute(PersistentSessionService.TRACKED_ATTRIBUTE, Boolean.TRUE);
            session.setAttribute(PersistentSessionService.SESSION_HASH_ATTRIBUTE, "hashed-session");
            holder[0] = session;
        } else {
            request.setSession(holder[0]);
        }
        return request;
    }

    private void stubRow(LocalDateTime idleExpiresAt, LocalDateTime expiresAt) {
        UserSession row = new UserSession();
        row.setId(10L);
        row.setUserId(7L);
        row.setTenantId("tenant-a");
        row.setSessionIdHash("hashed-session");
        row.setIdleExpiresAt(idleExpiresAt);
        row.setExpiresAt(expiresAt);
        when(userSessionMapper.selectBySessionHash("tenant-a", "hashed-session")).thenReturn(row);
    }

    @Test
    void 閉じる前に有効だったsessionは静止の間にidle期限を過ぎても有効でtouchを省き最終利用を持ち越す() {
        setUpQuiesce();
        HttpSession[] session = new HttpSession[1];
        stubRow(T0.plusMinutes(30), T0.plusHours(12));
        quiesceClock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(60));

        quiesceClock.set(T0.plusMinutes(45));
        assertTrue(quiesceService.validateAndTouch(trackedRequest(session), authentication));

        verify(userSessionMapper, never()).touch(any(), any(), any());
        verify(userSessionMapper, never()).touchCarriedOver(any(), any(), any());
        assertEquals(T0.plusMinutes(45), session[0].getAttribute(PersistentSessionService.QUIESCE_LAST_USE_ATTRIBUTE));
    }

    @Test
    void 静止の間に使い続けたsessionは解除の後の最初の要求でも失効せずtouchでidle期限が延び属性を消す() {
        setUpQuiesce();
        HttpSession[] session = new HttpSession[1];
        stubRow(T0.plusMinutes(30), T0.plusHours(12));
        quiesceClock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(60));
        quiesceClock.set(T0.plusMinutes(55));
        assertTrue(quiesceService.validateAndTouch(trackedRequest(session), authentication));

        quiesceClock.set(T0.plusMinutes(70));
        BackupQuiesceTestSupport.reopen(gate, 1);
        when(userSessionMapper.touchCarriedOver(10L, T0.plusMinutes(70), T0.plusMinutes(100))).thenReturn(1);
        assertTrue(quiesceService.validateAndTouch(trackedRequest(session), authentication));

        verify(userSessionMapper).touchCarriedOver(10L, T0.plusMinutes(70), T0.plusMinutes(100));
        verify(userSessionMapper, never()).touch(any(), any(), any());
        assertNull(session[0].getAttribute(PersistentSessionService.QUIESCE_LAST_USE_ATTRIBUTE));
    }

    @Test
    void 静止の間に使わなかったsessionは解除の後に行のidle期限で失効する() {
        setUpQuiesce();
        HttpSession[] session = new HttpSession[1];
        stubRow(T0.plusMinutes(30), T0.plusHours(12));
        quiesceClock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(60));
        quiesceClock.set(T0.plusMinutes(70));
        BackupQuiesceTestSupport.reopen(gate, 1);

        assertFalse(quiesceService.validateAndTouch(trackedRequest(session), authentication));
        verify(userSessionMapper, never()).touchCarriedOver(any(), any(), any());
    }

    @Test
    void 最大保持時間を超えた緩和は無く閉じた時点で期限切れのsessionも通さない() {
        setUpQuiesce();
        stubRow(T0.plusMinutes(30), T0.plusHours(12));
        quiesceClock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofHours(3));

        // 閉じてから 60 分（最長の保持時間）までは閉じた時刻で判定する
        quiesceClock.set(T0.plusMinutes(80));
        assertTrue(quiesceService.validateAndTouch(trackedRequest(new HttpSession[1]), authentication));
        // 60 分を超えると「今 − 60 分」で判定し、idle 期限（T0+30 分）を過ぎた行は通さない
        quiesceClock.set(T0.plusMinutes(91));
        assertFalse(quiesceService.validateAndTouch(trackedRequest(new HttpSession[1]), authentication));

        // 閉じた時点（T0+40 分）で idle 期限を過ぎていた session は静止の間も通さない
        BackupQuiesceTestSupport.reopen(gate, 1);
        quiesceClock.set(T0.plusMinutes(40));
        BackupQuiesceTestSupport.close(gate, 2, Duration.ofMinutes(60));
        quiesceClock.set(T0.plusMinutes(41));
        assertFalse(quiesceService.validateAndTouch(trackedRequest(new HttpSession[1]), authentication));
    }

    @Test
    void 静止の間も最長の有効期間expires_atは今の時刻で判定する() {
        setUpQuiesce();
        stubRow(T0.plusMinutes(30), T0.plusMinutes(40));
        quiesceClock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(60));

        quiesceClock.set(T0.plusMinutes(39));
        assertTrue(quiesceService.validateAndTouch(trackedRequest(new HttpSession[1]), authentication));
        quiesceClock.set(T0.plusMinutes(41));
        assertFalse(quiesceService.validateAndTouch(trackedRequest(new HttpSession[1]), authentication));
    }

    @Test
    void 静止中のlogoutの失効はlogoutの時点の時刻で取っておき解除の後に書く() {
        setUpQuiesce();
        MockHttpServletRequest request = trackedRequest(new HttpSession[1]);
        quiesceClock.set(T0.plusMinutes(5));

        PersistentSessionService.CapturedRevocation captured = quiesceService.captureCurrent(request, authentication,
                "LOGOUT");

        assertEquals(new PersistentSessionService.CapturedRevocation("tenant-a", "hashed-session", 7L,
                T0.plusMinutes(5), "LOGOUT"), captured);
        verify(userSessionMapper, never()).revoke(any(), any(), any());
        stubRow(T0.plusMinutes(30), T0.plusHours(12));
        quiesceClock.set(T0.plusMinutes(50));
        quiesceService.revokeCaptured(captured);
        verify(userSessionMapper).revoke(10L, T0.plusMinutes(5), "LOGOUT");
        assertTrue(captured.summary().contains("session=hashed-s"));
    }
}
