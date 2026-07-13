package com.ses.mapper;

import com.ses.BaseIntegrationTest;
import com.ses.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SysUserMapperTest extends BaseIntegrationTest {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    void testSelectByUsername_Success() {
        // admin は 002_init_master_data.sql で投入されていると想定
        SysUser user = sysUserMapper.selectByUsername("admin");
        assertNotNull(user);
        assertEquals("admin", user.getUsername());
        assertEquals(0, user.getDeletedFlag());
    }

    @Test
    void testSelectByUsername_NotFound() {
        SysUser user = sysUserMapper.selectByUsername("unknown_user");
        assertNull(user);
    }

    @Test
    void testUpdateLockState_Lock() {
        // 既存ユーザーの取得
        SysUser user = sysUserMapper.selectByUsername("admin");
        assertNotNull(user);

        LocalDateTime lockTime = LocalDateTime.now().plusMinutes(30);
        int updatedRows = sysUserMapper.updateLockState(user.getId(), 5, lockTime);

        assertEquals(1, updatedRows);

        SysUser updatedUser = sysUserMapper.selectById(user.getId());
        assertEquals(5, updatedUser.getFailedCount());
        assertNotNull(updatedUser.getLockedUntil());
        // H2とJavaの時間のナノ秒精度の違いがあるため、分単位までの検証に留めるか、IsAfterなどで検証
        assertTrue(updatedUser.getLockedUntil().isAfter(LocalDateTime.now()));
    }

    @Test
    void testUpdateLockState_Unlock() {
        SysUser user = sysUserMapper.selectByUsername("admin");
        
        // Lock
        sysUserMapper.updateLockState(user.getId(), 5, LocalDateTime.now().plusMinutes(10));
        
        // Unlock
        int updatedRows = sysUserMapper.updateLockState(user.getId(), 0, null);
        assertEquals(1, updatedRows);

        SysUser updatedUser = sysUserMapper.selectById(user.getId());
        assertEquals(0, updatedUser.getFailedCount());
        assertNull(updatedUser.getLockedUntil());
    }
}
