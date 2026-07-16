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
        LocalDateTime now = LocalDateTime.now(clock);
        // DB側の条件付き更新で加算することで、同時ログイン失敗によるlost updateを防止する。
        // モックや旧スキーマでは未実装の場合があるため、更新件数0のときのみ従来処理へフォールバックする。
        int updated = sysUserMapper.incrementLoginFailure(user.getId(), now, MAX_FAILED, LOCK_MINUTES);
        if (updated > 0) {
            return;
        }
        // ロック期間が終了した後は新しい失敗カウント周期を開始する。
        // failed_count=0 のまま残るロック方式と整合させ、期限直後の1回の失敗で
        // 即時再ロックされることを防ぐ。
        boolean lockExpired = user.getLockedUntil() != null && !user.getLockedUntil().isAfter(now);
        int current = lockExpired ? 0 : (user.getFailedCount() != null ? user.getFailedCount() : 0);
        int next = current + 1;
        if (next >= MAX_FAILED) {
            LocalDateTime lockedUntil = now.plusMinutes(LOCK_MINUTES);
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
