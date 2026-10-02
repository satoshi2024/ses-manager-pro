package com.ses.service.pwa;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.AccountLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** password失敗回数を呼出元transactionのrollbackから分離して確定する。 */
@Service
@RequiredArgsConstructor
public class PwaRestoreFailureRecorder {
    private final SysUserMapper sysUserMapper;
    private final AccountLockService accountLockService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(Long userId, String tenantId) {
        SysUser user = sysUserMapper.selectByIdAndTenant(userId, tenantId);
        if (user != null && user.getUsername() != null) {
            // V1 sys_user.usernameは全tenant共通UNIQUE。client指定値やtenant外検索値は渡さない。
            accountLockService.onLoginFailure(user.getUsername());
        }
    }
}
