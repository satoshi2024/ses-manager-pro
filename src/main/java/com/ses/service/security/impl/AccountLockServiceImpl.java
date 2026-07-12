package com.ses.service.security.impl;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.AccountLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * アカウントロックサービス実装。
 * Clockを注入することでテスト時に時刻を制御可能にする。
 */
@Slf4j
@Service
public class AccountLockServiceImpl implements AccountLockService {

    private final SysUserMapper sysUserMapper;
    private final Clock clock;

    public AccountLockServiceImpl(SysUserMapper sysUserMapper, Clock clock) {
        this.sysUserMapper = sysUserMapper;
        this.clock = clock;
    }

    @Override
    public void onLoginFailure(String username) {
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            return; // 存在しないユーザーは無視（ユーザー列挙対策）
        }
        int current = user.getFailedCount() != null ? user.getFailedCount() : 0;
        int next = current + 1;
        if (next >= MAX_FAILED) {
            LocalDateTime lockedUntil = LocalDateTime.now(clock).plusMinutes(LOCK_MINUTES);
            sysUserMapper.updateLockState(user.getId(), 0, lockedUntil);
            log.warn("アカウントをロックしました: username={} 解除予定={}", username, lockedUntil);
        } else {
            sysUserMapper.updateLockState(user.getId(), next, user.getLockedUntil());
        }
    }

    @Override
    public void onLoginSuccess(String username) {
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            return;
        }
        // 失敗回数・ロックをクリア
        if ((user.getFailedCount() != null && user.getFailedCount() > 0) || user.getLockedUntil() != null) {
            sysUserMapper.updateLockState(user.getId(), 0, null);
        }
    }
}
