package com.ses.config;

import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H2上でShedLockの時刻設定と取得・競合・解放経路を検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class SchedulerLockH2IntegrationTest {

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private Environment environment;

    @Test
    void H2ではDB時刻を無効化しlock取得競合解放を検知できる() {
        assertFalse(environment.getProperty("app.scheduler.lock.use-db-time", Boolean.class, true));

        String lockName = "scheduler-h2-regression-" + System.nanoTime();
        // createdAtは本番経路（@SchedulerLockのSpringLockConfigurationExtractor）と同じく
        // ClockProvider.now()（ミリ秒切捨て）で作る。生のInstant.now()はサブミリ秒を持つため、
        // lockAtLeastFor=ZEROの解放時刻がcreatedAtのまま書き込まれ、H2 TIMESTAMP(3)の四捨五入で
        // lock_untilが1ms先へ丸められると、同一ミリ秒内の再取得（lock_until <= now）が間欠的に失敗していた。
        // 本番経路のcreatedAtは既にミリ秒単位へ揃っているため、この揃え方は検証意図を変えない。
        LockConfiguration configuration = new LockConfiguration(
                ClockProvider.now(), lockName, Duration.ofMinutes(1), Duration.ZERO);

        Optional<SimpleLock> firstAttempt = assertDoesNotThrow(
                () -> lockProvider.lock(configuration),
                "H2のShedLock取得でDB product警告・例外を発生させない");
        assertTrue(firstAttempt.isPresent(), "scheduler lockを取得できること");
        SimpleLock firstLock = firstAttempt.orElseThrow();

        try {
            Optional<SimpleLock> secondAttempt = assertDoesNotThrow(
                    () -> lockProvider.lock(configuration),
                    "既存lockとの競合は例外ではなく未取得として扱うこと");
            assertTrue(secondAttempt.isEmpty(), "保持中の同一scheduler lockを二重取得しないこと");
        } finally {
            firstLock.unlock();
        }

        Optional<SimpleLock> afterUnlock = assertDoesNotThrow(
                () -> lockProvider.lock(configuration),
                "scheduler lock解放後の再取得でエラーを見逃さないこと");
        assertTrue(afterUnlock.isPresent(), "解放後はscheduler lockを再取得できること");
        afterUnlock.orElseThrow().unlock();
    }
}
