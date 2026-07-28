package com.ses.service.security;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** トランザクション外からの呼び出しで即時に世代が進むことを検証する。 */
class ScopeChangeInvalidatorTest {

    @Test
    void トランザクション外なら即時に世代を進める() {
        ScopeVersionRegistry registry = new ScopeVersionRegistry();
        ScopeChangeInvalidator invalidator = new ScopeChangeInvalidator(registry);
        long before = registry.current();

        invalidator.invalidate();

        assertEquals(before + 1, registry.current());
    }

    @Test
    void トランザクション中はcommit後だけ世代を進めrollbackでは変えない() {
        ScopeVersionRegistry registry = new ScopeVersionRegistry();
        ScopeChangeInvalidator invalidator = new ScopeChangeInvalidator(registry);
        long before = registry.current();

        TransactionSynchronizationManager.initSynchronization();
        try {
            invalidator.invalidate();
            assertEquals(before, registry.current(), "commit前は世代を変えない");
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertEquals(before + 1, registry.current(), "commit後に1世代だけ進める");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.initSynchronization();
        try {
            invalidator.invalidate();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertEquals(before + 1, registry.current(), "rollbackでは世代を進めない");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
