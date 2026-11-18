package com.ses.service.ai.copilot.dispatch;

import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.AiExternalSendAuditor;
import com.ses.service.ai.ExternalSendCostLedger;
import com.ses.service.ai.PersistentExternalSendCostLedger;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * NF08 B2c-1 §1.5: 実MySQL（Flyway適用済みV194・InnoDB）でdurable claim・commit後permit・永続費用ledgerを確かめる。
 * <ul>
 *   <li>commit成功後にだけpermitを返し、独立connectionからCLAIMED・予約entry・bucket・ATTEMPT監査を読む。</li>
 *   <li>二接続の競合では成功1件・STALE1件。待機は {@code performance_schema.data_lock_waits} のlock IDで確認し、
 *       {@code LOCK_DATA} に依存しない（{@link InnoDbRecordLockWaits}）。</li>
 *   <li>日次上限ちょうどの並行予約、commit直後の例外（ACK喪失）とcommit直前の失敗、再起動後の再claim拒否、1213の再試行。</li>
 *   <li>lock待ちはtransaction timeout（残deadline以下）で打ち切られ、既定50秒の {@code innodb_lock_wait_timeout} を待たない。</li>
 *   <li>tenant-Bのsentinel行は全列不変。</li>
 * </ul>
 * <p>run行の排他lockはFKを持たない列の更新とSELECT ... FOR UPDATEだけで取り、子表へのinsertによるFKのS lockを
 * 待機の確認対象に混ぜない（費用entryはFKを持たない）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class CopilotDispatchClaimMySqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final long WAIT_MILLIS = 30_000;

    @Autowired private CopilotDispatchClaimService claimService;
    @Autowired private AiRecommendationRunMapper runMapper;
    @Autowired private PersistentExternalSendCostLedger ledger;
    @Autowired private AiExternalSendAuditor auditor;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @AfterEach
    void clearThreadContexts() {
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void commit後にだけpermitを返し独立connectionでCLAIMED_予約_bucket_ATTEMPTを読みtenantBは全列不変() throws Exception {
        assertTrue(AopUtils.isAopProxy(claimService), "prepareReadyはSpring transaction proxyを通す");
        String scope = scope();
        DispatchTicket sentinel = prepare(TENANT_B, scope, 30, 10_000);
        Map<String, Object> sentinelBefore = runRow(sentinel.runId());
        DispatchTicket ticket = prepare(TENANT_A, scope, 30, 10_000);

        ClaimOutcome outcome = claimAs(TENANT_A, claimService, ticket);

        assertEquals(ClaimOutcome.Status.CLAIMED, outcome.status());
        assertEquals(1, outcome.transactionAttempts());
        ClaimPermit permit = outcome.permit();
        assertNotNull(permit);
        Map<String, Object> run = runRow(ticket.runId());
        assertEquals("CLAIMED", run.get("dispatch_state"));
        assertEquals(ticket.claimId(), claimIdText(ticket.runId()));
        assertEquals(LocalDateTime.ofInstant(permit.claimedAt(), ZoneOffset.UTC), run.get("dispatch_claimed_at"));
        assertNull(run.get("dispatch_started_at"), "dispatch_started_atはB2c-2のsocket直前の確認で記録する");
        assertEquals("PENDING", run.get("status"));
        Map<String, Object> entry = entryRow(ticket.claimId());
        assertEquals("RESERVED", entry.get("entry_state"));
        assertEquals(30, ((Number) entry.get("reserved_yen")).intValue());
        assertEquals(ticket.runId(), ((Number) entry.get("run_id")).longValue());
        assertEquals(PersistentExternalSendCostLedger.BucketKeys.of(permit.claimedAt()).dayKey(), entry.get("day_key"));
        assertEquals(30L, committed(scope, "DAY"));
        assertEquals(30L, committed(scope, "MONTH"));
        assertEquals(LocalDateTime.ofInstant(permit.claimedAt(), ZoneOffset.UTC),
                jdbc.queryForObject("SELECT clock_high_water_at FROM t_ai_external_send_cost_bucket "
                        + "WHERE cost_scope=? AND bucket_type='DAY'", LocalDateTime.class, scope));
        assertEquals(1, auditCount(ticket.claimId(), AiExternalSendAuditor.APPLICATION_ATTEMPT),
                "ATTEMPTのidempotency_keyはclaim IDの36文字表記");
        assertEquals(0, auditCount(ticket.claimId(), AiExternalSendAuditor.APPLICATION_RESULT),
                "成功時のRESULTは送信完了後（B2c-2/B2d）に記録する");
        assertEquals(sentinelBefore, runRow(sentinel.runId()), "tenant-Bのsentinel行は全列不変");
    }

    @Test
    void 二接続が同じREADY行をclaimすると成功は1件だけで他方はSTALEとなり予約は1件分() throws Exception {
        String scope = scope();
        DispatchTicket ticket = prepare(TENANT_A, scope, 30, 10_000);
        DispatchTicket sentinel = prepare(TENANT_B, scope, 30, 10_000);
        Map<String, Object> sentinelBefore = runRow(sentinel.runId());
        DispatchTicket copy = DispatchClaimFixtures.copyOf(ticket);
        // 新しいscopeへの同時INSERT ... ON DUPLICATE KEY UPDATEの待機を、run行での待機の確認に混ぜない。
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ledger.ensureBuckets(scope, PersistentExternalSendCostLedger.BucketKeys.of(clock.instant())));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT id FROM t_ai_recommendation_run WHERE id=? FOR UPDATE")) {
                lock.setLong(1, ticket.runId());
                try (ResultSet row = lock.executeQuery()) {
                    assertTrue(row.next());
                }
            }
            long blockerId = connectionId(blocker);
            InnoDbRecordLockWaits.requireSinglePointLock(jdbc, blockerId, "t_ai_recommendation_run", "PRIMARY",
                    InnoDbRecordLockWaits.primaryKey(ticket.runId()), "NF08 claim競合");
            CountDownLatch start = new CountDownLatch(1);
            Future<ClaimOutcome> first = pool.submit(afterLatch(start, () -> claimAs(TENANT_A, claimService, ticket)));
            Future<ClaimOutcome> second = pool.submit(afterLatch(start, () -> claimAs(TENANT_A, claimService, copy)));
            start.countDown();
            awaitWaiters(blockerId, "t_ai_recommendation_run", "PRIMARY", 2, List.of(first, second));
            blocker.rollback();

            List<ClaimOutcome> outcomes = List.of(first.get(WAIT_MILLIS, TimeUnit.MILLISECONDS),
                    second.get(WAIT_MILLIS, TimeUnit.MILLISECONDS));
            assertEquals(1, outcomes.stream().filter(ClaimOutcome::claimed).count());
            ClaimOutcome loser = outcomes.stream().filter(outcome -> !outcome.claimed()).findFirst().orElseThrow();
            assertEquals(CopilotDispatchClaimService.REASON_STALE, loser.reasonCode());
            assertNull(loser.permit());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("CLAIMED", runRow(ticket.runId()).get("dispatch_state"));
        assertEquals(1, entryCount(scope), "予約entryは1件");
        assertEquals(30L, committed(scope, "DAY"), "予約は一回分だけ");
        assertEquals(2, auditCount(ticket.claimId(), AiExternalSendAuditor.APPLICATION_ATTEMPT),
                "ATTEMPTは両者が記録してよい（at-least-once）");
        assertEquals(1, auditCount(ticket.claimId(), AiExternalSendAuditor.APPLICATION_RESULT), "STALE側のRESULT");
        assertEquals(sentinelBefore, runRow(sentinel.runId()));
    }

    @Test
    void 日次上限ちょうどの並行予約は上限内の件数だけ成功し残りはNOT_SENTのままREADYに残る() throws Exception {
        String scope = scope();
        int requests = 5;
        List<DispatchTicket> tickets = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            tickets.add(prepare(TENANT_A, scope, 30, 90));
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ledger.ensureBuckets(scope, PersistentExternalSendCostLedger.BucketKeys.of(clock.instant())));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        List<ClaimOutcome> outcomes = new ArrayList<>();
        try {
            List<Future<ClaimOutcome>> futures = new ArrayList<>();
            for (DispatchTicket ticket : tickets) {
                futures.add(pool.submit(afterLatch(start, () -> claimAs(TENANT_A, claimService, ticket))));
            }
            start.countDown();
            for (Future<ClaimOutcome> future : futures) {
                outcomes.add(future.get(WAIT_MILLIS, TimeUnit.MILLISECONDS));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(3, outcomes.stream().filter(ClaimOutcome::claimed).count());
        assertEquals(2, outcomes.stream().filter(outcome -> ExternalSendCostLedger.REASON_DAILY.equals(outcome.reasonCode()))
                .count());
        assertEquals(90L, committed(scope, "DAY"), "上限ちょうどまで");
        assertEquals(3, entryCount(scope));
        for (int i = 0; i < requests; i++) {
            String expected = outcomes.get(i).claimed() ? "CLAIMED" : "READY";
            assertEquals(expected, runRow(tickets.get(i).runId()).get("dispatch_state"));
            if (!outcomes.get(i).claimed()) {
                assertEquals(1, auditCount(tickets.get(i).claimId(), AiExternalSendAuditor.APPLICATION_RESULT));
                assertEquals(ExternalSendCostLedger.REASON_DAILY, resultErrorCode(tickets.get(i).claimId()));
            }
        }
    }

    @Test
    void 日次上限を超える見積りはNOT_SENTで拒否し行とbucketを変えない() throws Exception {
        String scope = scope();
        DispatchTicket ticket = prepare(TENANT_A, scope, 31, 30);
        Map<String, Object> before = runRow(ticket.runId());

        ClaimOutcome outcome = claimAs(TENANT_A, claimService, ticket);

        assertEquals(ExternalSendCostLedger.REASON_DAILY, outcome.reasonCode());
        assertNull(outcome.permit());
        assertEquals(before, runRow(ticket.runId()));
        assertEquals(0L, committed(scope, "DAY"));
        assertEquals(0, entryCount(scope));
        assertTrue(resultAfterState(ticket.claimId()).contains("yenState=NOT_SENT"));
    }

    @Test
    void commit直後の例外ではpermitを返さず読み直しでCLAIMEDなら予約を維持し新しいinstanceでも再claimしない() throws Exception {
        String scope = scope();
        DispatchTicket ticket = prepare(TENANT_A, scope, 30, 10_000);
        CopilotDispatchClaimService ackLoss = instance(new FailingCommitTransactionManager(transactionManager, 2, false));

        ClaimOutcome outcome = claimAs(TENANT_A, ackLoss, ticket);

        assertEquals(ClaimOutcome.Status.UNCERTAIN, outcome.status());
        assertNull(outcome.permit(), "commitの成否を呼出側が確認できない場合はpermitを返さない");
        assertEquals(DispatchState.CLAIMED, outcome.observedState());
        assertEquals("CLAIMED", runRow(ticket.runId()).get("dispatch_state"));
        assertEquals("RESERVED", entryRow(ticket.claimId()).get("entry_state"), "CLAIMEDなら予約を維持する");
        assertEquals(30L, committed(scope, "DAY"));
        assertEquals(CopilotDispatchClaimService.REASON_CLAIM_UNCERTAIN, resultErrorCode(ticket.claimId()));
        assertTrue(resultAfterState(ticket.claimId()).contains("yenState=UPPER_BOUND"));

        Map<String, Object> runBefore = runRow(ticket.runId());
        Map<String, Object> entryBefore = entryRow(ticket.claimId());
        ClaimOutcome again = claimAs(TENANT_A, ackLoss, ticket);
        assertEquals(CopilotDispatchClaimService.REASON_ALREADY_ATTEMPTED, again.reasonCode());
        // 再起動を模擬: プロセス内の状態を持たない新しいinstanceと、同じbindingを知る者が作った写し。
        ClaimOutcome restarted = claimAs(TENANT_A, instance(transactionManager), DispatchClaimFixtures.copyOf(ticket));
        assertEquals(CopilotDispatchClaimService.REASON_STALE, restarted.reasonCode());
        assertNull(restarted.permit());
        assertEquals(runBefore, runRow(ticket.runId()));
        assertEquals(entryBefore, entryRow(ticket.claimId()));
        assertEquals(30L, committed(scope, "DAY"), "再claimで予約を積み増さない");
    }

    @Test
    void commit直前の失敗ではpermitを返さず読み直しでREADYなら予約は残らず同じattemptを再claimしない() throws Exception {
        String scope = scope();
        DispatchTicket ticket = prepare(TENANT_A, scope, 30, 10_000);
        CopilotDispatchClaimService failing = instance(new FailingCommitTransactionManager(transactionManager, 2, true));

        ClaimOutcome outcome = claimAs(TENANT_A, failing, ticket);

        assertEquals(ClaimOutcome.Status.UNCERTAIN, outcome.status());
        assertNull(outcome.permit());
        assertEquals(DispatchState.READY, outcome.observedState());
        assertEquals("READY", runRow(ticket.runId()).get("dispatch_state"));
        assertEquals(0, entryCount(scope));
        assertEquals(0L, committed(scope, "DAY"));
        assertTrue(resultAfterState(ticket.claimId()).contains("yenState=NOT_SENT"));
        assertEquals(CopilotDispatchClaimService.REASON_ALREADY_ATTEMPTED,
                claimAs(TENANT_A, failing, ticket).reasonCode());
        assertEquals("READY", runRow(ticket.runId()).get("dispatch_state"));
    }

    @Test
    void 再起動後の新しいinstanceは同じbindingの写しでもCLAIMED行を再claimせず全列を変えない() throws Exception {
        String scope = scope();
        DispatchTicket ticket = prepare(TENANT_A, scope, 30, 10_000);
        assertTrue(claimAs(TENANT_A, claimService, ticket).claimed());
        Map<String, Object> runBefore = runRow(ticket.runId());
        Map<String, Object> entryBefore = entryRow(ticket.claimId());
        long dayBefore = committed(scope, "DAY");

        ClaimOutcome restarted = claimAs(TENANT_A, instance(transactionManager), DispatchClaimFixtures.copyOf(ticket));

        assertEquals(ClaimOutcome.Status.NOT_CLAIMED, restarted.status());
        assertEquals(CopilotDispatchClaimService.REASON_STALE, restarted.reasonCode());
        assertEquals(runBefore, runRow(ticket.runId()));
        assertEquals(entryBefore, entryRow(ticket.claimId()));
        assertEquals(dayBefore, committed(scope, "DAY"));
    }

    @Test
    void deadlockの犠牲になったclaimはrollback後に同じclaim_IDで再試行し予約を二重にしない() throws Exception {
        String scope = scope();
        DispatchTicket ticket = prepare(TENANT_A, scope, 30, 10_000);
        // claimの事前生成は既存行の重複になり、blockerの行やlockに触れない。
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ledger.ensureBuckets(scope, PersistentExternalSendCostLedger.BucketKeys.of(clock.instant())));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            long blockerId = connectionId(blocker);
            // (1) blockerを重いtransactionにする（InnoDBは変更行数の少ない側を犠牲にする）。重みはclaimが触れない
            //     別scopeのbucket行に積む。
            // (2) blocker自身の文で、claimの予約IDが入る一意索引のgapに明示lockを取る（存在しない鍵のFOR UPDATE。
            //     REPEATABLE READではgap lockになる）。未commit行の暗黙lockは使わない。初回の実MySQLでは、
            //     重複する未commit行を待つinsertがperformance_schemaにもinnodb_trxにもlock待機として現れず、
            //     待機を確認できなかったため、他の試験で待機を観測できている「blocker自身が作る明示lock」に揃える。
            // claimはlock順 run → DAY → MONTH → entry のとおり、run行とbucketをlockした後のentry insertで
            // このgap lockを待つ（insert intention）。その後blockerがrun行を要求すると循環し、InnoDBが検出する。
            String weightScope = "nf08-weight-" + UUID.randomUUID().toString().substring(0, 8);
            try (PreparedStatement weight = blocker.prepareStatement("INSERT INTO t_ai_external_send_cost_bucket "
                    + "(cost_scope, bucket_type, bucket_key) VALUES (?, 'DAY', ?)")) {
                for (int i = 1; i <= 20; i++) {
                    weight.setString(1, weightScope);
                    weight.setString(2, String.format("2000-01-%02d", i));
                    weight.executeUpdate();
                }
            }
            try (PreparedStatement gap = blocker.prepareStatement(
                    "SELECT id FROM t_ai_external_send_cost_entry WHERE reservation_id=UUID_TO_BIN(?, 0) FOR UPDATE")) {
                gap.setString(1, ticket.claimId());
                try (ResultSet row = gap.executeQuery()) {
                    assertFalse(row.next(), "予約IDのentryはまだ無い（gapだけをlockする）");
                }
            }
            if (InnoDbRecordLockWaits.grantedRecordLocks(jdbc, blockerId, "t_ai_external_send_cost_entry",
                    "uk_ai_cost_entry_reservation", java.util.Set.of("X", "X,GAP")).isEmpty()) {
                fail("blockerが予約IDの一意索引にgap lockを持っていない: "
                        + InnoDbRecordLockWaits.inspectVisibility(jdbc, blockerId).explain());
            }
            Future<ClaimOutcome> claim = pool.submit(() -> claimAs(TENANT_A, claimService, ticket));
            awaitWaiterOnBlockerLock(blockerId, "t_ai_external_send_cost_entry", "uk_ai_cost_entry_reservation", claim);
            // claimはrun行の排他lockを保持したままentryのinsertで待っている。blockerがrun行を要求すると循環する。
            try (PreparedStatement cycle = blocker.prepareStatement(
                    "SELECT id FROM t_ai_recommendation_run WHERE id=? FOR UPDATE")) {
                cycle.setLong(1, ticket.runId());
                try (ResultSet row = cycle.executeQuery()) {
                    assertTrue(row.next(), "blockerは犠牲にならず、claim側が1213でrollbackされる");
                }
            }
            blocker.rollback();

            ClaimOutcome outcome = claim.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            assertEquals(ClaimOutcome.Status.CLAIMED, outcome.status());
            assertEquals(2, outcome.transactionAttempts(), "1213の後に同じticketで一度だけ再試行する");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(30L, committed(scope, "DAY"), "犠牲側の予約はrollbackされ二重にならない");
        assertEquals(1, entryCount(scope));
        assertEquals(1, auditCount(ticket.claimId(), AiExternalSendAuditor.APPLICATION_ATTEMPT),
                "再試行でATTEMPTを重複させない");
    }

    /**
     * 計画 §1.6（lock待ちの上限）: claimのlock待ちは残deadline以下のtransaction timeout（MyBatisのstatement timeoutへ
     * 適用される）で打ち切られ、既定50秒の {@code innodb_lock_wait_timeout} を待たない。blockerがrun行のlockを
     * timeoutより長く保持しても、claimは予約・entry・行を変えずに送信前のRESULTを残して返る。
     */
    @Test
    void lock待ちはtransaction_timeoutで打ち切られ既定50秒を待たずに予約なしで返る() throws Exception {
        String scope = scope();
        // deadlineは約6秒先。claim transactionのtimeoutは残deadlineを秒へ切り捨てた約5秒になる。
        DispatchTicket ticket = prepare(TENANT_A, scope, 30, 10_000, java.time.Duration.ofSeconds(6));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ledger.ensureBuckets(scope, PersistentExternalSendCostLedger.BucketKeys.of(clock.instant())));
        Map<String, Object> runBefore = runRow(ticket.runId());
        long dayBefore = committed(scope, "DAY");
        long monthBefore = committed(scope, "MONTH");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ClaimOutcome outcome;
        long elapsedMillis;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT id FROM t_ai_recommendation_run WHERE id=? FOR UPDATE")) {
                lock.setLong(1, ticket.runId());
                try (ResultSet row = lock.executeQuery()) {
                    assertTrue(row.next());
                }
            }
            long blockerId = connectionId(blocker);
            InnoDbRecordLockWaits.PointLock anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, blockerId,
                    "t_ai_recommendation_run", "PRIMARY", InnoDbRecordLockWaits.primaryKey(ticket.runId()),
                    "NF08 claim lock待ち上限");
            long started = System.nanoTime();
            Future<ClaimOutcome> claim = pool.submit(() -> claimAs(TENANT_A, claimService, ticket));
            InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor, null,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, 5_000,
                    () -> {
                        if (claim.isDone()) {
                            fail("claimがrun行のlockを待たずに終了した: " + claim.get());
                        }
                    }, "NF08 claim lock待ち上限");
            // blockerはlockを保持したまま。claimはtimeoutで自ら戻らなければならない。
            outcome = claim.get(20, TimeUnit.SECONDS);
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(1, InnoDbRecordLockWaits.grantedRecordLocks(jdbc, blockerId, "t_ai_recommendation_run",
                    "PRIMARY", InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES).size(), "blockerはlockを保持したまま");
            blocker.rollback();
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(ClaimOutcome.Status.NOT_CLAIMED, outcome.status());
        assertNull(outcome.permit());
        assertTrue(Set.of(CopilotDispatchClaimService.REASON_LEDGER_UNAVAILABLE,
                CopilotDispatchClaimService.REASON_DEADLINE).contains(outcome.reasonCode()), outcome.reasonCode());
        assertTrue(elapsedMillis < 15_000, "既定の50秒のlock待ちに依存しない: elapsed=" + elapsedMillis + "ms");
        assertEquals(runBefore, runRow(ticket.runId()), "行はREADYのまま全列不変");
        assertEquals(0, entryCount(scope));
        assertEquals(dayBefore, committed(scope, "DAY"));
        assertEquals(monthBefore, committed(scope, "MONTH"));
        assertEquals(1, auditCount(ticket.claimId(), AiExternalSendAuditor.APPLICATION_ATTEMPT));
        assertEquals(outcome.reasonCode(), resultErrorCode(ticket.claimId()));
        assertTrue(resultAfterState(ticket.claimId()).contains("yenState=NOT_SENT"));
    }

    // ---- fixtures ----

    private DispatchTicket prepare(String tenant, String scope, int upperBoundYen, long dailyLimitYen) {
        return prepare(tenant, scope, upperBoundYen, dailyLimitYen, java.time.Duration.ofMinutes(5));
    }

    private DispatchTicket prepare(String tenant, String scope, int upperBoundYen, long dailyLimitYen,
                                   java.time.Duration deadlineFromNow) {
        Long artifactId = jdbc.queryForObject("SELECT id FROM m_ai_artifact_version "
                + "WHERE use_case='MANAGEMENT_COPILOT' AND status='ACTIVE' AND deleted_flag=0 LIMIT 1", Long.class);
        assertNotNull(artifactId);
        String traceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO t_ai_recommendation_run (tenant_id, trace_id, use_case, artifact_version_id, "
                        + "input_hash, redacted_summary_json, status, status_version) "
                        + "VALUES (?, ?, 'MANAGEMENT_COPILOT', ?, ?, ?, 'PENDING', 0)",
                tenant, traceId, artifactId, DispatchClaimFixtures.CONFIG_HASH,
                "{\"attemptKind\":\"SUMMARY\",\"outcome\":\"PENDING\",\"costState\":\"UNKNOWN\"}");
        long runId = jdbc.queryForObject("SELECT id FROM t_ai_recommendation_run WHERE trace_id=?", Long.class,
                traceId);
        // fixtureのdeadlineは「nowNanos + 5分」なので、指定した残り時間になるようnowNanosをずらす。
        long nowNanos = System.nanoTime() - TimeUnit.MINUTES.toNanos(5) + deadlineFromNow.toNanos();
        DispatchIssueRequest request = DispatchClaimFixtures.request(artifactId, clock.instant(), nowNanos,
                upperBoundYen, dailyLimitYen, 1_000_000, scope);
        return AccountingTenantContextHolder.runWithTenant(tenant, () -> claimService.prepareReady(
                DispatchClaimFixtures.summaryAttempt(runId, traceId, tenant, artifactId), request));
    }

    private ClaimOutcome claimAs(String tenant, CopilotDispatchClaimService service, DispatchTicket ticket) {
        AccountingTenantContextHolder.setTenantId(tenant);
        try {
            return service.claim(ticket);
        } finally {
            AccountingTenantContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private CopilotDispatchClaimService instance(PlatformTransactionManager manager) {
        return new CopilotDispatchClaimService(runMapper, ledger, auditor, manager, clock, System::nanoTime,
                new SecureRandom());
    }

    private static String scope() {
        return "nf08-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static <T> Callable<T> afterLatch(CountDownLatch start, Callable<T> body) {
        return () -> {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("開始待ちが時間切れになりました");
            }
            return body.call();
        };
    }

    /** blockerの保持lockへ、指定数のrequester接続がlock IDの待機辺で到達するまで待つ（LOCK_DATAは使わない）。 */
    private void awaitWaiters(long blockerId, String table, String index, int expected, List<Future<ClaimOutcome>> workers)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        Set<Long> reached = new LinkedHashSet<>();
        while (System.nanoTime() < deadline) {
            for (Future<ClaimOutcome> worker : workers) {
                if (worker.isDone()) {
                    fail("claimがblockerの解放を待たずに終了した: " + worker.get());
                }
            }
            List<InnoDbRecordLockWaits.HeldLock> held = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, blockerId,
                    table, index, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES);
            if (held.size() == 1) {
                List<InnoDbRecordLockWaits.WaitEdge> waits = InnoDbRecordLockWaits.waitEdgesOnIndex(jdbc, table, index);
                reached.clear();
                for (InnoDbRecordLockWaits.WaitEdge edge : waits) {
                    long requester = edge.requesterConnectionId();
                    if (requester != blockerId && InnoDbRecordLockWaits.reachesHolder(waits, requester, blockerId,
                            held.get(0).lockId())) {
                        reached.add(requester);
                    }
                }
                if (reached.size() >= expected) {
                    return;
                }
            }
            Thread.sleep(25);
        }
        fail("blockerの排他lockを" + expected + "接続が待つことを確認できなかった: reached=" + reached + " "
                + InnoDbRecordLockWaits.describeWaitsForBlocker(jdbc, blockerId));
    }

    /**
     * blockerが保持する {@code table}・{@code index} の明示lockを、別の接続（claim）が待っていることを確認する。
     * 待機辺は2通りで探す: blockerのsessionが作ったlock（THREAD_ID）と、blockerのtransactionが所有するlock
     * （ENGINE_TRANSACTION_ID。{@code information_schema.innodb_trx} を使わない）。LOCK_DATAは使わない。
     * claimが待ちを続けている間（transactionのtimeout前）に確認できなければ、権限不足か待機が無いかを切り分けて失敗させる。
     */
    private void awaitWaiterOnBlockerLock(long blockerId, String table, String index, Future<ClaimOutcome> worker)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (worker.isDone()) {
                fail("claimがblockerのlockを待つ間に待機を確認できなかった: " + worker.get() + " "
                        + InnoDbRecordLockWaits.inspectVisibility(jdbc, blockerId).explain());
            }
            List<InnoDbRecordLockWaits.WaitEdge> edges = new ArrayList<>(
                    InnoDbRecordLockWaits.waitEdges(jdbc, null, blockerId, table, index));
            edges.addAll(InnoDbRecordLockWaits.waitEdgesOwnedBy(jdbc, blockerId, table, index));
            for (InnoDbRecordLockWaits.WaitEdge edge : edges) {
                if (edge.requesterConnectionId() != blockerId && "WAITING".equals(edge.requesterStatus())
                        && "RECORD".equals(edge.requesterType())) {
                    return;
                }
            }
            Thread.sleep(25);
        }
        fail("claimがentryのinsertでblockerのgap lockを待つことを確認できなかった: "
                + InnoDbRecordLockWaits.inspectVisibility(jdbc, blockerId).explain());
    }

    private static long connectionId(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    // ---- independent readback（新しいconnection。byte列は16進文字列にして全列を比較できる形にする） ----

    private Map<String, Object> runRow(long runId) throws Exception {
        return row("SELECT * FROM t_ai_recommendation_run WHERE id=?", runId);
    }

    private Map<String, Object> entryRow(String reservationId) throws Exception {
        return row("SELECT * FROM t_ai_external_send_cost_entry WHERE reservation_id=UUID_TO_BIN(?, 0)", reservationId);
    }

    private String claimIdText(long runId) {
        return jdbc.queryForObject("SELECT BIN_TO_UUID(dispatch_claim_id, 0) FROM t_ai_recommendation_run WHERE id=?",
                String.class, runId);
    }

    private long committed(String scope, String type) {
        List<Long> values = jdbc.queryForList("SELECT committed_yen FROM t_ai_external_send_cost_bucket "
                + "WHERE cost_scope=? AND bucket_type=?", Long.class, scope, type);
        return values.isEmpty() ? 0L : values.get(0);
    }

    private int entryCount(String scope) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_ai_external_send_cost_entry WHERE cost_scope=?",
                Integer.class, scope);
        return count == null ? 0 : count;
    }

    private int auditCount(String claimId, String applicationCode) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE idempotency_key=? "
                + "AND application_code=?", Integer.class, claimId, applicationCode);
        return count == null ? 0 : count;
    }

    private String resultErrorCode(String claimId) {
        return jdbc.queryForObject("SELECT error_code FROM t_audit_log WHERE idempotency_key=? AND application_code=? "
                + "ORDER BY id LIMIT 1", String.class, claimId, AiExternalSendAuditor.APPLICATION_RESULT);
    }

    private String resultAfterState(String claimId) {
        return jdbc.queryForObject("SELECT after_state FROM t_audit_log WHERE idempotency_key=? AND application_code=? "
                + "ORDER BY id LIMIT 1", String.class, claimId, AiExternalSendAuditor.APPLICATION_RESULT);
    }

    private Map<String, Object> row(String sql, Object key) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, key);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "行がありません");
                ResultSetMetaData metadata = result.getMetaData();
                Map<String, Object> columns = new LinkedHashMap<>();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    Object value = result.getObject(column);
                    if (value instanceof byte[] bytes) {
                        value = HexFormat.of().formatHex(bytes);
                    } else if (value instanceof java.sql.Timestamp timestamp) {
                        value = timestamp.toLocalDateTime();
                    }
                    columns.put(metadata.getColumnLabel(column), value);
                }
                assertFalse(result.next());
                return columns;
            }
        }
    }

    /**
     * 指定回目のcommitで失敗させるtransaction manager。{@code beforeCommit=false} はcommitしてから例外を投げ
     * （ACK喪失）、{@code true} はrollbackしてから例外を投げる（commit前の失敗）。どちらもcallbackは完了している。
     */
    private static final class FailingCommitTransactionManager implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private final int failOn;
        private final boolean beforeCommit;
        private final AtomicInteger commits = new AtomicInteger();

        FailingCommitTransactionManager(PlatformTransactionManager delegate, int failOn, boolean beforeCommit) {
            this.delegate = delegate;
            this.failOn = failOn;
            this.beforeCommit = beforeCommit;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            if (commits.incrementAndGet() != failOn) {
                delegate.commit(status);
                return;
            }
            if (beforeCommit) {
                delegate.rollback(status);
                throw new TransactionSystemException("simulated failure before commit");
            }
            delegate.commit(status);
            throw new TransactionSystemException("simulated acknowledgement loss after commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }
}
