package com.ses.service.security.impl;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * アカウントロックサービスの単体テスト（P8 Task7）。
 * 5回失敗でロック・上限未満は加算・成功でリセットを固定Clockで検証する。
 */
class AccountLockServiceImplTest {

    private SysUserMapper mapper;
    private AccountLockServiceImpl service;
    private final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 10, 0);

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(SysUserMapper.class);
        Clock fixed = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new AccountLockServiceImpl(mapper, fixed);
    }

    private SysUser user(int failedCount) {
        SysUser u = new SysUser();
        u.setId(1L);
        u.setUsername("tester");
        u.setFailedCount(failedCount);
        return u;
    }

    @Test
    void onLoginFailure_上限未満は失敗回数を加算する() {
        when(mapper.selectByUsernameForUpdate("tester")).thenReturn(user(2));

        service.onLoginFailure("tester");

        verify(mapper).selectByUsernameForUpdate("tester");
        verify(mapper).updateLockState(eq(1L), eq(3), isNull());
    }

    @Test
    void onLoginFailure_5回目でロックしlocked_untilは30分後() {
        when(mapper.selectByUsernameForUpdate("tester")).thenReturn(user(4)); // 4 → 5でロック

        service.onLoginFailure("tester");

        verify(mapper).selectByUsernameForUpdate("tester");
        ArgumentCaptor<LocalDateTime> cap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).updateLockState(eq(1L), eq(0), cap.capture());
        assertEquals(NOW.plusMinutes(30), cap.getValue(), "解除予定は現在時刻+30分");
    }

    @Test
    void onLoginFailure_存在しないユーザーは何もしない() {
        when(mapper.selectByUsernameForUpdate("ghost")).thenReturn(null);

        service.onLoginFailure("ghost");

        verify(mapper, never()).updateLockState(anyLong(), anyInt(), any());
    }

    @Test
    void onLoginFailure_期限切れロックは新しい周期の1回目として数える() {
        SysUser expired = user(4);
        expired.setLockedUntil(NOW);
        when(mapper.selectByUsernameForUpdate("tester")).thenReturn(expired);

        service.onLoginFailure("tester");

        verify(mapper).updateLockState(1L, 1, null);
    }

    @Test
    void onLoginFailure_有効なロック期限は後続失敗で変更しない() {
        LocalDateTime lockedUntil = NOW.plusMinutes(30);
        SysUser locked = user(0);
        locked.setLockedUntil(lockedUntil);
        when(mapper.selectByUsernameForUpdate("tester")).thenReturn(locked);

        service.onLoginFailure("tester");

        verify(mapper, never()).updateLockState(anyLong(), anyInt(), any());
    }

    @Test
    void onLoginFailure_信頼済みuserIdとtenantで行ロック取得する() {
        when(mapper.selectByIdAndTenantForUpdate(1L, "tenant-a")).thenReturn(user(4));

        service.onLoginFailure(1L, "tenant-a");

        verify(mapper).selectByIdAndTenantForUpdate(1L, "tenant-a");
        ArgumentCaptor<LocalDateTime> cap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).updateLockState(eq(1L), eq(0), cap.capture());
        assertEquals(NOW.plusMinutes(30), cap.getValue());
    }

    @Test
    void onLoginSuccess_失敗回数がある場合はリセットする() {
        when(mapper.selectByUsername("tester")).thenReturn(user(3));

        service.onLoginSuccess("tester");

        verify(mapper).updateLockState(eq(1L), eq(0), isNull());
    }

    @Test
    void onLoginSuccess_失敗もロックも無ければ更新しない() {
        when(mapper.selectByUsername("tester")).thenReturn(user(0));

        service.onLoginSuccess("tester");

        verify(mapper, never()).updateLockState(anyLong(), anyInt(), any());
    }
}
