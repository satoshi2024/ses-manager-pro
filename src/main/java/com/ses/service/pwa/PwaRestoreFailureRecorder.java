package com.ses.service.pwa;

import com.ses.service.security.AccountLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** password失敗回数を呼出元transactionのrollbackから分離して確定する。 */
@Service
@RequiredArgsConstructor
public class PwaRestoreFailureRecorder {
    private final AccountLockService accountLockService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(Long userId, String tenantId) {
        accountLockService.onLoginFailure(userId, tenantId);
    }
}
