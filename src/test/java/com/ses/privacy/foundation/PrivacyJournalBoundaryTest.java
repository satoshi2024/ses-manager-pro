package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.CommandType;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalCommand;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyJournalBoundaryTest {
    @Test
    void activeAndDefaultProfile拒否は接続取得前に終わる() {
        AtomicInteger attempts = new AtomicInteger();
        DataSource tripwire = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) { attempts.incrementAndGet(); throw new AssertionError("接続前に拒否される必要があります"); }
                    return null;
                });
        for (String[] active : List.of(new String[]{"prod"}, new String[]{"prod", "test"}, new String[]{})) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles(active);
                context.refresh();
                assertThrows(SecurityException.class, () -> new JdbcPrivacyJournalAdapter(tripwire, context.getEnvironment(), Clock.systemUTC()));
                assertThrows(SecurityException.class, () -> JournalTestConnectionCapability.fromOwnedContainer(null, context));
            }
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("test");
            context.getEnvironment().setDefaultProfiles("prod");
            context.refresh();
            assertThrows(SecurityException.class, () -> new JdbcPrivacyJournalAdapter(tripwire, context.getEnvironment(), Clock.systemUTC()));
            assertThrows(SecurityException.class, () -> JournalTestConnectionCapability.fromOwnedContainer(null, context));
        }
        assertEquals(0, attempts.get());
        assertEquals(0, JdbcPrivacyJournalAdapter.class.getConstructors().length,
                "adapterにpublic raw-DataSource constructorがない");
    }

    @Test
    void commandHashはtenantやtypedpayloadから独立に偽装できない() {
        JournalPartition partition = new JournalPartition("tenant-a", "subject-a");
        JournalCommand good = JournalCommand.create("cmd-1", partition, 0, CommandType.HOLD_STARTED,
                null, null, null, null, 0, null);
        assertThrows(IllegalArgumentException.class, () -> new JournalCommand(good.commandId(), "0".repeat(64),
                partition, good.expectedRevision(), good.type(), good.caseId(), good.actionId(), good.requestHash(),
                good.workerId(), good.expectedLeaseGeneration(), good.leaseUntil()));
        assertNotEquals(good.payloadHash(), JournalCommand.create("cmd-2", new JournalPartition("tenant-b", "subject-a"),
                0, CommandType.HOLD_STARTED, null, null, null, null, 0, null).payloadHash());
    }

    @Test
    void lease時刻はMySQLのmicrosecond精度へ正規化される() {
        JournalPartition partition = new JournalPartition("tenant-a", "subject-a");
        Instant nanos = Instant.parse("2026-10-02T00:00:00.123456789Z");
        JournalCommand fromNanos = JournalCommand.create("lease-a", partition, 0, CommandType.LEASE_CLAIMED,
                null, null, null, "worker-a", 0, nanos);
        JournalCommand fromMicros = JournalCommand.create("lease-b", partition, 0, CommandType.LEASE_CLAIMED,
                null, null, null, "worker-a", 0, Instant.parse("2026-10-02T00:00:00.123456Z"));
        assertEquals(fromMicros.leaseUntil(), fromNanos.leaseUntil());
        assertEquals(fromMicros.payloadHash(), fromNanos.payloadHash());
    }
}
