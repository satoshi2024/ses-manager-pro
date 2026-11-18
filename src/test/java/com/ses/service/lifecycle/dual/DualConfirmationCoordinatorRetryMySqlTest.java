package com.ses.service.lifecycle.dual;

import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.dual.DualConfirmationResultDto;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskDepMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.document.DocumentEvidenceStorageVerifier;
import com.ses.service.lifecycle.LifecycleNotificationService;
import com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.Fixture;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.capture;
import static com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.decisionBody;
import static com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.principal;
import static com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.submitBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * M9（NF01細粒度epoch計画 §6.1・§5.6・§5.8・§7.8 Demo (8)、W3-21a）: 実MySQL 8でcoordinatorの再試行と拒否eventを確かめる。
 *
 * <ul>
 *   <li>試行の中の {@code @@transaction_isolation} は {@code READ-COMMITTED}。外側transactionからの呼出しは拒否する。</li>
 *   <li>lock取得後に依存先が変わると試行をrollbackし、新しいdiscoveryからやり直す。3回目も変われば409
 *       {@code error.lifecycle.concurrentUpdateRetryExhausted}。</li>
 *   <li>1205（実際のlock待ちtimeout）を予算に数える。1213（deadlock）と文の実行中のtimeoutは試行の中で注入し、
 *       期限切れ後のtimeout（{@code TransactionTimedOutException}）は実際に試行のtimeoutを超えて起こす。</li>
 *   <li>拒否event（{@code DENIED}）は試行のrollback後も残り、提出・確認の行は残らない。</li>
 * </ul>
 *
 * <p>B0の不変表はtriggerが削除を拒否するため後始末で行を消さない。専用のMySQL containerを使い、試験ごとに新しい
 * 利用者・案件・タスク・文書で分離し、B0の行と拒否eventはタスクIDで絞って数える（tenantは配備の {@code default} に固定。
 * {@link DualConfirmationMySqlFixtures}）。試行の数は {@link HookedLifecycleLockExecutor} のlock取得の呼出し
 * （試行ごとに1回）で数える。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Import(HookedLifecycleLockExecutor.Configuration.class)
class DualConfirmationCoordinatorRetryMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_dual_retry")
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
        registry.add("app.features.nf01.enabled", () -> "true");
        registry.add("app.features.nf01-dual-confirmation.enabled", () -> "true");
    }

    private static final long WAIT_SECONDS = 30;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private LifecycleDualConfirmationCoordinator coordinator;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private EngineerSalesMapper engineerSalesMapper;
    @Autowired private UserOrganizationMapper userOrganizationMapper;
    @Autowired private LifecycleTemplateMapper templateMapper;
    @Autowired private LifecycleCaseMapper caseMapper;
    @Autowired private LifecycleTaskMapper taskMapper;
    @Autowired private LifecycleTaskDepMapper taskDepMapper;
    @Autowired private HookedLifecycleLockExecutor lockExecutor;
    @MockBean private DocumentEvidenceStorageVerifier storageVerifier;
    @MockBean private LifecycleNotificationService notificationService;

    private DualConfirmationMySqlFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new DualConfirmationMySqlFixtures(jdbc, sysUserMapper, engineerMapper, engineerSalesMapper,
                userOrganizationMapper, templateMapper, caseMapper, taskMapper, taskDepMapper);
        reset(storageVerifier);
        when(storageVerifier.verify(any())).thenReturn(DocumentEvidenceStorageVerifier.Outcome.MATCH);
        lockExecutor.reset(null);
    }

    @AfterEach
    void tearDown() {
        lockExecutor.reset(null);
    }

    // ===================================================================== transaction境界

    @Test
    void 試行の中の分離レベルはREAD_COMMITTEDで外側transactionからの呼出しは拒否する() {
        Fixture f = fixtures.create("w321a-m9-iso");
        AtomicReference<String> isolation = new AtomicReference<>();
        lockExecutor.reset(new HookedLifecycleLockExecutor.Hook() {
            @Override
            public void before(int n) {
                isolation.set(jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            }
        });

        DualConfirmationResultDto submitted = submit(f, fixtures.taskGeneration(f));

        assertEquals("READ-COMMITTED", isolation.get());
        assertEquals("PENDING", submitted.getState());

        Fixture other = fixtures.create("w321a-m9-ambient");
        lockExecutor.reset(null);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> outer.execute(status -> submit(other, 0L)));
        assertEquals(0, lockExecutor.calls(), "ambient transactionでは試行を始めない");
        assertEquals(0, submissions(other));
        assertEquals(List.of(), fixtures.deniedReasons(other));
    }

    // ===================================================================== 予算に数える例外

    @Test
    void lock取得後に依存先が変わり続けると新しいdiscoveryで3回試し409でrollback後も拒否eventが残る() throws Exception {
        Fixture f = fixtures.create("w321a-m9-dep");
        DualConfirmationResultDto submitted = submit(f, fixtures.taskGeneration(f));
        long extra = fixtures.extraTask(f, "EXTRA_SUCCESSOR");
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            lockExecutor.reset(null);
            lockExecutor.reset(new HookedLifecycleLockExecutor.Hook() {
                @Override
                public void before(int n) throws Exception {
                    // discoveryの後・lockの前に、後続の依存を別の接続でcommitして増減させる（lock後の再読込がplanと食い違う）
                    other.submit(() -> {
                        if (n % 2 == 1) {
                            jdbc.update("INSERT INTO t_lifecycle_task_dep (case_id, predecessor_task_id, "
                                    + "successor_task_id) VALUES (?, ?, ?)", f.caseId(), f.taskId(), extra);
                        } else {
                            jdbc.update("DELETE FROM t_lifecycle_task_dep WHERE case_id = ? AND "
                                    + "predecessor_task_id = ? AND successor_task_id = ?", f.caseId(), f.taskId(), extra);
                        }
                    }).get(WAIT_SECONDS, TimeUnit.SECONDS);
                }
            });

            Throwable outcome = capture(() -> coordinator.decide(f.taskId(), submitted.getSubmissionId(),
                    principal(f, f.hr1()), decisionBody(submitted, "CONFIRM", "確認"), "key-confirm-1"));

            assertBusiness(outcome, 409, "error.lifecycle.concurrentUpdateRetryExhausted");
            assertEquals(3, lockExecutor.calls(), "試行は3回まで");
        } finally {
            other.shutdownNow();
        }
        assertDeniedOnce(f, "RETRY_EXHAUSTED", 409, 3, "CONFIRM");
        assertEquals(0, fixtures.confirmations(f), "rollbackした試行の確認行は残らない");
        assertEquals("IN_PROGRESS", fixtures.taskStatus(f.taskId()));
    }

    @Test
    void 実際の1205を予算に数え3回目で409になり拒否eventが残る() throws Exception {
        Fixture f = fixtures.create("w321a-m9-1205");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Object> blocker = pool.submit(() -> required().execute(status -> {
                jdbc.queryForObject("SELECT id FROM t_lifecycle_case WHERE id = ? FOR UPDATE", Long.class, f.caseId());
                locked.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertTrue(locked.await(WAIT_SECONDS, TimeUnit.SECONDS), "blockerが案件行をlockできない");
            lockExecutor.reset(new HookedLifecycleLockExecutor.Hook() {
                @Override
                public void before(int n) {
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                }

                @Override
                public void cleanup(int n) {
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout = 50");
                }
            });

            Throwable outcome = capture(() -> submit(f, 0L));

            assertBusiness(outcome, 409, "error.lifecycle.concurrentUpdateRetryExhausted");
            assertEquals(3, lockExecutor.calls());
            release.countDown();
            blocker.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertDeniedOnce(f, "RETRY_EXHAUSTED", 409, 3, "SUBMIT");
        assertEquals(0, submissions(f));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deadlock", "statement-timeout"})
    void 一回目の1213や文の実行中timeoutは予算に数え次の試行で成功する(String failure) {
        Fixture f = fixtures.create("w321a-m9-" + failure);
        lockExecutor.reset(new HookedLifecycleLockExecutor.Hook() {
            @Override
            public void before(int n) {
                if (n == 1) {
                    throw "deadlock".equals(failure)
                            ? new PessimisticLockingFailureException("Deadlock found when trying to get lock")
                            : new QueryTimeoutException("Statement cancelled due to timeout");
                }
            }
        });

        DualConfirmationResultDto submitted = submit(f, 0L);

        assertEquals("PENDING", submitted.getState());
        assertEquals(2, lockExecutor.calls());
        assertEquals(1, submissions(f));
        assertEquals(List.of(), fixtures.deniedReasons(f));
    }

    @Test
    void 試行のtimeoutを超えた後の次の文のTransactionTimedOutExceptionを予算に数え次の試行で成功する() {
        Fixture f = fixtures.create("w321a-m9-txtimeout");
        lockExecutor.reset(new HookedLifecycleLockExecutor.Hook() {
            @Override
            public void after(int n) throws Exception {
                if (n == 1) {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(LifecycleDualConfirmationAttemptService.ATTEMPT_TIMEOUT_SECONDS)
                            + 500);
                }
            }
        });

        DualConfirmationResultDto submitted = submit(f, 0L);

        assertEquals("PENDING", submitted.getState());
        assertEquals(2, lockExecutor.calls());
        assertEquals(1, submissions(f));
        assertEquals(List.of(), fixtures.deniedReasons(f));
    }

    // ===================================================================== 決定的な拒否と拒否event

    @Test
    void 決定的な拒否は再試行せず試行のrollback後も拒否eventが残る() {
        Fixture f = fixtures.create("w321a-m9-deny");

        Throwable outcome = capture(() -> submit(f, 5L));

        assertBusiness(outcome, 409, "error.lifecycle.submissionStale");
        assertEquals(1, lockExecutor.calls(), "決定的な拒否は再試行しない");
        assertEquals(0, submissions(f), "rollbackした試行の提出行は残らない");
        assertDeniedOnce(f, "SUBMISSION_STALE", 409, 1, "SUBMIT");
        Map<String, Object> row = jdbc.queryForMap("SELECT case_id, requested_task_id, actor_user_id, "
                + "task_generation FROM t_lifecycle_dual_event WHERE tenant_id = ? AND requested_task_id = ? "
                + "AND event_type = 'DENIED'", f.tenant(), f.taskId());
        assertEquals(f.caseId(), ((Number) row.get("case_id")).longValue());
        assertEquals(f.taskId(), ((Number) row.get("requested_task_id")).longValue());
        assertEquals(f.sales(), ((Number) row.get("actor_user_id")).longValue());
        assertEquals(0L, ((Number) row.get("task_generation")).longValue());
        assertFalse(fixtures.count("SELECT COUNT(*) FROM t_lifecycle_dual_event WHERE tenant_id = ? AND task_id = ? "
                + "AND event_type <> 'DENIED'", f.tenant(), f.taskId()) > 0, "rollbackした試行のeventは残らない");
    }

    // ===================================================================== helpers

    private DualConfirmationResultDto submit(Fixture f, long generation) {
        return coordinator.submit(f.taskId(), principal(f, f.sales()), DualConfirmationChannel.INTERNAL,
                submitBody(generation, f, "提出します"), "key-submit-1");
    }

    private long submissions(Fixture f) {
        return fixtures.submissions(f);
    }

    private void assertDeniedOnce(Fixture f, String reason, int status, int attempts, String operation) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT reason_code, http_status, attempt_count, operation "
                + "FROM t_lifecycle_dual_event WHERE tenant_id = ? AND requested_task_id = ? AND event_type = 'DENIED'",
                f.tenant(), f.taskId());
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertEquals(reason, row.get("reason_code"));
        assertEquals(status, ((Number) row.get("http_status")).intValue());
        assertEquals(attempts, ((Number) row.get("attempt_count")).intValue());
        assertEquals(operation, row.get("operation"));
    }

    private TransactionTemplate required() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static void assertBusiness(Throwable outcome, int status, String key) {
        assertThat(outcome).as("期待した拒否 %s", key).isInstanceOf(BusinessException.class);
        BusinessException business = (BusinessException) outcome;
        assertEquals(status, business.getCode(), key);
        assertEquals(key, business.getMessageKey());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
