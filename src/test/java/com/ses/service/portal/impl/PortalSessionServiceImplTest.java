package com.ses.service.portal.impl;

import com.ses.backup.quiesce.BackupQuiesceProperties;
import com.ses.backup.quiesce.BackupQuiesceTestSupport;
import com.ses.backup.quiesce.BackupWriteGate;
import com.ses.common.util.ClientIpResolver;
import com.ses.common.util.SecurityHashUtil;
import com.ses.config.PortalSecurityProperties;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalSession;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalSessionMapper;
import com.ses.mapper.PortalTermsConsentMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.PortalUserPermissionMapper;
import com.ses.portal.PortalLoginUser;
import com.ses.service.SystemConfigService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RB-QUIESCE U-15（design.md §6.4）: backup の静止中も portal の参照を続けるための portal の session の扱い。
 *
 * <p>静止中（gate が書込みを許さず permit が無い）は、session の行へ書かない（touch・期限切れの失効）。idle は gate が閉じた時刻で
 * 判定し、静止中の最終利用を memory に持ち越して解除の後の最初の要求で touch する。緩和は最大保持時間まで。失効と絶対期限は
 * 今の時刻で判定し、持越しで有効に戻さない。社内の session の U-14（{@code PersistentSessionServiceImplTest}）と同じ規則。
 */
class PortalSessionServiceImplTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 9, 0);
    private static final String COOKIE = "PORTAL_SESSION";

    private final MutableClock clock = new MutableClock(T0.toInstant(ZoneOffset.UTC));
    private final Map<String, PortalSession> rows = new HashMap<>();
    private PortalSessionMapper sessionMapper;
    private PortalSessionServiceImpl service;
    private BackupWriteGate gate;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(PortalSessionMapper.class);
        PortalUserMapper userMapper = mock(PortalUserMapper.class);
        PortalOrganizationMapper organizationMapper = mock(PortalOrganizationMapper.class);
        PortalUserPermissionMapper permissionMapper = mock(PortalUserPermissionMapper.class);
        PortalTermsConsentMapper termsConsentMapper = mock(PortalTermsConsentMapper.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(sessionMapper.selectByTokenHash(anyString())).thenAnswer(call -> rows.get(call.<String>getArgument(0)));
        when(sessionMapper.touchIfValid(anyLong(), any(), any())).thenReturn(1);
        when(sessionMapper.touchCarriedOver(anyLong(), any(), any())).thenReturn(1);
        PortalUser user = new PortalUser();
        user.setId(7L);
        user.setPortalOrgId(3L);
        user.setStatus("ACTIVE");
        user.setEmail("bq@example.test");
        when(userMapper.selectById(anyLong())).thenReturn(user);
        PortalOrganization org = new PortalOrganization();
        org.setId(3L);
        org.setStatus("ACTIVE");
        org.setType("CUSTOMER");
        when(organizationMapper.selectById(anyLong())).thenReturn(org);
        when(permissionMapper.selectPermissionKeys(anyLong())).thenReturn(List.of());
        when(systemConfigService.getString(eq("portal.terms.current-version"), any())).thenReturn("1");
        when(termsConsentMapper.latestConsentedVersion(anyLong())).thenReturn("1");

        service = new PortalSessionServiceImpl(sessionMapper, userMapper, organizationMapper, permissionMapper,
                termsConsentMapper, systemConfigService, new PortalSecurityProperties(), mock(ClientIpResolver.class),
                clock);
        // heartbeat の thread は無い。単調時計を止めて FENCED にも手元の期限にもしない
        gate = BackupQuiesceTestSupport.openGate(false, () -> 0L, clock);
        service.setBackupWriteGate(gate);
        BackupQuiesceProperties quiesce = new BackupQuiesceProperties();
        quiesce.setMaxHoldFull(Duration.ofMinutes(60));
        service.setBackupQuiesceProperties(quiesce);
    }

    @Test
    void 静止していなければ従来どおりtouchのCASで有効を確かめる() {
        session(1L, "a", T0.plusMinutes(30), T0.plusHours(12));

        assertThat(service.resolve(request("a"))).isNotNull();

        verify(sessionMapper).touchIfValid(1L, T0, T0.plusMinutes(30));
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void 静止中の参照はtouchせずに有効でidle期限を過ぎても静止の間は有効() {
        session(1L, "a", T0.plusMinutes(30), T0.plusHours(12));
        clock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(60));

        clock.set(T0.plusMinutes(25));
        PortalLoginUser principal = service.resolve(request("a"));
        clock.set(T0.plusMinutes(55));
        PortalLoginUser later = service.resolve(request("a"));

        assertThat(principal).isNotNull();
        assertThat(later).as("閉じた時点で有効だった session は、静止の間に idle 期限を過ぎても有効").isNotNull();
        verifyNoSessionWrite();
        assertThat(service.quiescedUseCount()).isEqualTo(1);
    }

    @Test
    void 解除の後の最初の要求は失効せず持ち越した最終利用でtouchする() {
        session(1L, "a", T0.plusMinutes(30), T0.plusHours(12));
        clock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(60));
        clock.set(T0.plusMinutes(55));
        assertThat(service.resolve(request("a"))).isNotNull();
        clock.set(T0.plusMinutes(70));
        BackupQuiesceTestSupport.reopen(gate, 1);

        PortalLoginUser afterReopen = service.resolve(request("a"));

        assertThat(afterReopen).as("行の idle 期限（+30 分）は過ぎたが、静止中の最終利用（+55 分）から 30 分の内").isNotNull();
        verify(sessionMapper).touchCarriedOver(1L, T0.plusMinutes(70), T0.plusMinutes(100));
        verify(sessionMapper, never()).touchIfValid(anyLong(), any(), any());
        verify(sessionMapper, never()).update(any(), any());
        assertThat(service.quiescedUseCount()).as("touch の後は持越しを消す").isZero();
    }

    @Test
    void 静止中に使わなかったsessionは解除の後に今の時刻のidleで失効する() {
        session(1L, "a", T0.plusMinutes(30), T0.plusHours(12));
        clock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(60));
        clock.set(T0.plusMinutes(70));
        BackupQuiesceTestSupport.reopen(gate, 1);

        assertThat(service.resolve(request("a"))).isNull();

        verify(sessionMapper).update(isNull(), any());
        verify(sessionMapper, never()).touchCarriedOver(anyLong(), any(), any());
    }

    @Test
    void 最大保持時間を超える緩和は無く静止中は失効を書かずに未認証にし解除の後も有効に戻らない() {
        session(1L, "a", T0.plusMinutes(30), T0.plusHours(12));
        clock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofHours(2));

        // idle 期限（+30 分）から 61 分後: 緩和の上限（最長の保持時間 60 分）を超える
        clock.set(T0.plusMinutes(91));
        assertThat(service.resolve(request("a"))).isNull();
        verifyNoSessionWrite();
        assertThat(service.quiescedUseCount()).as("失効した session の持越しは作らない").isZero();

        clock.set(T0.plusMinutes(95));
        BackupQuiesceTestSupport.reopen(gate, 1);
        assertThat(service.resolve(request("a"))).isNull();
        verify(sessionMapper).update(isNull(), any());
        verify(sessionMapper, never()).touchCarriedOver(anyLong(), any(), any());
    }

    @Test
    void 失効とexpires_atは静止中も今の時刻で直ちに効く() {
        session(1L, "a", T0.plusMinutes(30), T0.plusMinutes(40));
        PortalSession revoked = session(2L, "b", T0.plusMinutes(30), T0.plusHours(12));
        revoked.setRevokedAt(T0.plusMinutes(5));
        clock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(60));

        clock.set(T0.plusMinutes(25));
        assertThat(service.resolve(request("a"))).isNotNull();
        assertThat(service.resolve(request("b"))).as("失効した session").isNull();
        clock.set(T0.plusMinutes(41));
        assertThat(service.resolve(request("a"))).as("絶対期限（+40 分）は今の時刻で判定").isNull();
        verifyNoSessionWrite();

        // 解除の後も持越しで有効に戻らない（絶対期限は今の時刻、失効は行）
        clock.set(T0.plusMinutes(45));
        BackupQuiesceTestSupport.reopen(gate, 1);
        assertThat(service.resolve(request("a"))).isNull();
        assertThat(service.resolve(request("b"))).isNull();
        verify(sessionMapper, never()).touchCarriedOver(anyLong(), any(), any());
        verify(sessionMapper, never()).touchIfValid(anyLong(), any(), any());
    }

    @Test
    void permitを持つ要求は静止中でも従来どおりtouchする() {
        session(1L, "a", T0.plusMinutes(30), T0.plusHours(12));

        // 閉じる前に取った permit（更新の要求）を持つ thread は、閉じかけた gate でも書ける
        try (var permit = gate.tryAcquire(com.ses.backup.quiesce.BackupWritePermit.Kind.HTTP, "portal")) {
            assertThat(permit).isNotNull();
            BackupQuiesceTestSupport.beginClosing(gate, 1);
            assertThat(service.resolve(request("a"))).isNotNull();
        }
        verify(sessionMapper).touchIfValid(eq(1L), any(), any());
    }

    @Test
    void 持越しの上限に達したら記録せず解除の後はidle期限で失効するだけで緩和を広げない() {
        service.setQuiesceCarryMax(1);
        session(1L, "a", T0.plusMinutes(30), T0.plusHours(12));
        session(2L, "b", T0.plusMinutes(30), T0.plusHours(12));
        clock.set(T0.plusMinutes(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(60));
        clock.set(T0.plusMinutes(25));
        assertThat(service.resolve(request("a"))).isNotNull();
        assertThat(service.resolve(request("b"))).as("静止中は上限に達しても通す（記録しないだけ）").isNotNull();
        assertThat(service.quiescedUseCount()).isEqualTo(1);
        verifyNoSessionWrite();

        clock.set(T0.plusMinutes(40));
        BackupQuiesceTestSupport.reopen(gate, 1);
        clearInvocations(sessionMapper);
        assertThat(service.resolve(request("a"))).isNotNull();
        assertThat(service.resolve(request("b"))).as("持越しの無い session は行の idle 期限で失効").isNull();
        verify(sessionMapper, times(1)).touchCarriedOver(eq(1L), any(), any());
        verify(sessionMapper, times(1)).update(isNull(), any());
    }

    // ---------------------------------------------------------------- helper

    private void verifyNoSessionWrite() {
        verify(sessionMapper, never()).touchIfValid(anyLong(), any(), any());
        verify(sessionMapper, never()).touchCarriedOver(anyLong(), any(), any());
        verify(sessionMapper, never()).update(any(), any());
        verify(sessionMapper, never()).updateById(any(PortalSession.class));
    }

    private PortalSession session(long id, String token, LocalDateTime idleExpiresAt, LocalDateTime expiresAt) {
        PortalSession session = new PortalSession();
        session.setId(id);
        session.setUserId(7L);
        session.setTokenHash(SecurityHashUtil.sha256(token));
        session.setIssuedAt(T0);
        session.setLastSeenAt(T0);
        session.setIdleExpiresAt(idleExpiresAt);
        session.setExpiresAt(expiresAt);
        rows.put(session.getTokenHash(), session);
        return session;
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portal/auth/me");
        request.setCookies(new Cookie(COOKIE, token));
        return request;
    }

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
}
