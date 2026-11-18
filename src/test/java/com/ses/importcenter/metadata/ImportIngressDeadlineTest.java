package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 総期限のphase確認・残り秒数・timeout分類を、注入clockだけで確認する。 */
class ImportIngressDeadlineTest {
    private final AtomicLong clock = new AtomicLong(1_000L);

    @Test
    void checkPassesInsideBudgetAndFailsClosedAtTheDeadline() {
        ImportIngressDeadline deadline = ImportIngressDeadline.start(clock::get, Duration.ofSeconds(120));

        clock.addAndGet(Duration.ofSeconds(119).toNanos());
        deadline.check("bodyRead");
        clock.addAndGet(Duration.ofSeconds(1).toNanos());

        BusinessException expired = assertThrows(BusinessException.class, () -> deadline.check("bodyRead"));
        assertEquals(503, expired.getCode());
        assertEquals(ImportIngressDeadline.EXCEEDED_MESSAGE_KEY, expired.getMessageKey());
        assertTrue(deadline.expired());
    }

    @Test
    void transactionSecondsAreFlooredAndBelowOneSecondIsRejected() {
        ImportIngressDeadline deadline = ImportIngressDeadline.start(clock::get, Duration.ofSeconds(120));

        clock.addAndGet(Duration.ofMillis(20_500).toNanos());
        assertEquals(99, deadline.requireTransactionSeconds("commit"));
        clock.addAndGet(Duration.ofMillis(98_000).toNanos());
        assertEquals(1, deadline.requireTransactionSeconds("commit"));
        clock.addAndGet(Duration.ofMillis(600).toNanos());

        BusinessException expired = assertThrows(BusinessException.class,
                () -> deadline.requireTransactionSeconds("commit"));
        assertEquals(503, expired.getCode());
    }

    @Test
    void timeoutClassificationFollowsTheCauseChainOnly() {
        assertTrue(ImportIngressDeadline.isTimeoutFailure(new TransactionTimedOutException("synthetic")));
        assertTrue(ImportIngressDeadline.isTimeoutFailure(new QueryTimeoutException("synthetic")));
        assertTrue(ImportIngressDeadline.isTimeoutFailure(new DataAccessResourceFailureException("wrapped",
                new IllegalStateException("middle", new SQLTimeoutException("synthetic statement timeout")))));
        assertTrue(ImportIngressDeadline.isTimeoutFailure(
                new ImportCommitOutcomeUnknownException(new QueryTimeoutException("synthetic"))));

        assertFalse(ImportIngressDeadline.isTimeoutFailure(new CannotAcquireLockException("synthetic lock wait")));
        assertFalse(ImportIngressDeadline.isTimeoutFailure(new CannotCreateTransactionException("synthetic pool")));
        assertFalse(ImportIngressDeadline.isTimeoutFailure(null));
    }

    @Test
    void cyclicOrDeepCauseChainsTerminateWithoutTimeoutMatch() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        RuntimeException deep = new RuntimeException("leaf");
        for (int depth = 0; depth < 40; depth++) deep = new RuntimeException("level", deep);

        assertFalse(ImportIngressDeadline.isTimeoutFailure(first));
        assertFalse(ImportIngressDeadline.isTimeoutFailure(deep));
    }
}
