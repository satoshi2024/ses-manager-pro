package com.ses.service.training;

import com.ses.entity.LearningPlan;
import com.ses.entity.Engineer;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingEnrollment;
import com.ses.mapper.LearningPlanEventMapper;
import com.ses.mapper.LearningPlanMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingEnrollmentMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.ses.common.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 同一計画の異なるenrollment操作をInnoDBの計画行ロックで直列化する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class TrainingPlanCompletionConcurrencyMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf03_training_plan_concurrency")
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

    @Autowired private TrainingPlanService service;
    @Autowired private LearningPlanMapper planMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private TrainingCourseMapper courseMapper;
    @Autowired private TrainingEnrollmentMapper enrollmentMapper;
    @SpyBean private LearningPlanEventMapper eventMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void 異なるenrollmentの完了を実ロックで直列化しplanとeventを両方確定する() throws Exception {
        Fixture f = fixture("complete-race", 2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstChanged = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong blockingConnectionId = new AtomicLong();
        AtomicLong requestingConnectionId = new AtomicLong();
        try {
            Future<?> first = pool.submit(() -> {
                tenant();
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        blockingConnectionId.set(connectionId());
                        planMapper.selectByIdForUpdateWithTenant(f.planId, "default");
                        service.completeEnrollment(f.enrollmentIds.get(0), 0,
                                LocalDate.of(2026, 9, 1), null, 1L);
                        firstChanged.countDown();
                        await(releaseFirst);
                    });
                } finally { AccountingTenantContextHolder.clear(); }
            });
            assertTrue(firstChanged.await(10, TimeUnit.SECONDS), "先行transactionがplan lockを保持する");
            assertTrue(hasGrantedPlanRecordLock(f.planId, blockingConnectionId.get()),
                    "先行connectionが対象planのPRIMARY RECORD lockを保持する。locks=" + locks());
            Future<?> second = pool.submit(() -> {
                tenant();
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        requestingConnectionId.set(connectionId());
                        secondStarted.countDown();
                        service.completeEnrollment(f.enrollmentIds.get(1), 0,
                                LocalDate.of(2026, 9, 2), null, 1L);
                    });
                } finally { AccountingTenantContextHolder.clear(); }
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "後続transactionがworkerで開始する");
            awaitLockWait(second, f.planId, requestingConnectionId, blockingConnectionId);
            releaseFirst.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertEquals(TrainingPlanService.PLAN_COMPLETED, plan(f.planId).getStatus());
            assertEquals(2, eventMapper.selectByPlanId(f.planId).stream()
                    .filter(e -> "ENROLLMENT_COMPLETED".equals(e.getEventType())).count());
            assertThrows(BusinessException.class, () -> service.completeEnrollment(f.enrollmentIds.get(1), 0,
                    LocalDate.of(2026, 9, 2), null, 1L), "同じversionの完了再送は拒否する");
            assertEquals(2, eventMapper.selectByPlanId(f.planId).stream()
                    .filter(e -> "ENROLLMENT_COMPLETED".equals(e.getEventType())).count());
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void event書込失敗時はenrollmentとplanの更新も同じtransactionでrollbackする() {
        Fixture f = fixture("event-rollback", 1);
        reset(eventMapper);
        doThrow(new IllegalStateException("event storage unavailable")).when(eventMapper).insertEvent(any());
        try {
            assertThrows(IllegalStateException.class, () -> service.completeEnrollment(f.enrollmentIds.get(0), 0,
                    LocalDate.of(2026, 9, 7), null, 1L));
        } finally {
            reset(eventMapper);
        }
        TrainingEnrollment afterEnrollment = enrollment(f.enrollmentIds.get(0));
        assertEquals(TrainingPlanService.ENROLLMENT_STARTED, afterEnrollment.getStatus());
        assertEquals(0, afterEnrollment.getVersion());
        assertEquals(TrainingPlanService.PLAN_IN_PROGRESS, plan(f.planId).getStatus());
        assertEquals(0, plan(f.planId).getVersion());
        assertEquals(0, eventMapper.selectByPlanId(f.planId).size());
    }

    @Test
    void 最終完了と新規enrollの競合では先に確定したenrollmentを含めて判定する() throws Exception {
        Fixture f = fixture("enroll-race", 1);
        TrainingCourse extra = course("enroll-race-extra");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch enrolled = new CountDownLatch(1);
        CountDownLatch completionStarted = new CountDownLatch(1);
        CountDownLatch releaseEnroll = new CountDownLatch(1);
        AtomicLong blockingConnectionId = new AtomicLong();
        AtomicLong requestingConnectionId = new AtomicLong();
        try {
            Future<?> first = pool.submit(() -> {
                tenant();
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        blockingConnectionId.set(connectionId());
                        planMapper.selectByIdForUpdateWithTenant(f.planId, "default");
                        service.enroll(f.planId, 0, extra.getId(), 1L);
                        enrolled.countDown();
                        await(releaseEnroll);
                    });
                } finally { AccountingTenantContextHolder.clear(); }
            });
            assertTrue(enrolled.await(10, TimeUnit.SECONDS), "enrollment insert後もplan lockを保持する");
            assertTrue(hasGrantedPlanRecordLock(f.planId, blockingConnectionId.get()),
                    "enroll connectionが対象planのPRIMARY RECORD lockを保持する。locks=" + locks());
            Future<?> completion = pool.submit(() -> {
                tenant();
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        requestingConnectionId.set(connectionId());
                        completionStarted.countDown();
                        service.completeEnrollment(f.enrollmentIds.get(0), 0,
                                LocalDate.of(2026, 9, 3), null, 1L);
                    });
                } finally { AccountingTenantContextHolder.clear(); }
            });
            assertTrue(completionStarted.await(5, TimeUnit.SECONDS), "完了transactionがworkerで開始する");
            awaitLockWait(completion, f.planId, requestingConnectionId, blockingConnectionId);
            releaseEnroll.countDown();
            first.get(20, TimeUnit.SECONDS);
            completion.get(20, TimeUnit.SECONDS);
            LearningPlan after = plan(f.planId);
            assertEquals(TrainingPlanService.PLAN_IN_PROGRESS, after.getStatus());
            Long newEnrollment = jdbc.queryForObject("SELECT id FROM t_training_enrollment WHERE plan_id=? AND course_id=?",
                    Long.class, f.planId, extra.getId());
            assertEquals(TrainingPlanService.ENROLLMENT_PLANNED, enrollment(newEnrollment).getStatus());
            service.startEnrollment(newEnrollment, 0, 1L);
            service.completeEnrollment(newEnrollment, 1, LocalDate.of(2026, 9, 4), null, 1L);
            assertEquals(TrainingPlanService.PLAN_COMPLETED, plan(f.planId).getStatus());
        } finally {
            releaseEnroll.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 完了取消の同時実行を両方のplanロック取得順で直列化する() throws Exception {
        assertCompleteCancelRace("complete-first", true);
        assertCompleteCancelRace("cancel-first", false);
    }

    private void assertCompleteCancelRace(String suffix, boolean completeFirst) throws Exception {
        Fixture f = fixture(suffix, 2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstChanged = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong blockingConnectionId = new AtomicLong();
        AtomicLong requestingConnectionId = new AtomicLong();
        try {
            Future<?> first = pool.submit(() -> {
                tenant();
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        blockingConnectionId.set(connectionId());
                        planMapper.selectByIdForUpdateWithTenant(f.planId, "default");
                        if (completeFirst) complete(f.enrollmentIds.get(0), LocalDate.of(2026, 9, 5));
                        else service.cancelEnrollment(f.enrollmentIds.get(0), 0, 1L, "受講不要");
                        firstChanged.countDown();
                        await(releaseFirst);
                    });
                } finally { AccountingTenantContextHolder.clear(); }
            });
            assertTrue(firstChanged.await(10, TimeUnit.SECONDS), "先行operationがplan lockを保持する");
            assertTrue(hasGrantedPlanRecordLock(f.planId, blockingConnectionId.get()),
                    "先行connectionが対象planのPRIMARY RECORD lockを保持する。locks=" + locks());
            Future<?> second = pool.submit(() -> {
                tenant();
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        requestingConnectionId.set(connectionId());
                        secondStarted.countDown();
                        if (completeFirst) service.cancelEnrollment(f.enrollmentIds.get(1), 0, 1L, "受講不要");
                        else complete(f.enrollmentIds.get(1), LocalDate.of(2026, 9, 6));
                    });
                } finally { AccountingTenantContextHolder.clear(); }
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "後続operationが独立connectionで開始する");
            awaitLockWait(second, f.planId, requestingConnectionId, blockingConnectionId);
            releaseFirst.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertEquals(TrainingPlanService.PLAN_COMPLETED, plan(f.planId).getStatus());
            assertEquals(1, eventMapper.selectByPlanId(f.planId).stream()
                    .filter(e -> "ENROLLMENT_COMPLETED".equals(e.getEventType())).count());
            assertEquals(1, eventMapper.selectByPlanId(f.planId).stream()
                    .filter(e -> "ENROLLMENT_CANCELLED".equals(e.getEventType())).count());
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private void complete(Long enrollmentId, LocalDate completedOn) {
        service.completeEnrollment(enrollmentId, 0, completedOn, null, 1L);
    }

    private Fixture fixture(String suffix, int count) {
        tenant();
        Engineer engineer = Engineer.builder().tenantId("default").legalEntityId(1L)
                .fullName("NF03 " + suffix).employmentType("正社員").status("Bench").build();
        engineerMapper.insert(engineer);
        TrainingCourse[] courses = new TrainingCourse[count];
        for (int i = 0; i < count; i++) courses[i] = course(suffix + "-" + i);
        LearningPlan plan = new LearningPlan();
        plan.setTenantId("default"); plan.setEngineerId(engineer.getId()); plan.setCreatedByUserId(1L);
        plan.setTitle("NF03 " + suffix); plan.setAttainmentCriteria("完了確認");
        plan.setPlannedCostJpy(BigDecimal.ZERO); plan.setStatus(TrainingPlanService.PLAN_IN_PROGRESS);
        plan.setVersion(0); planMapper.insert(plan);
        Fixture f = new Fixture(plan.getId());
        for (TrainingCourse course : courses) {
            TrainingEnrollment e = new TrainingEnrollment();
            e.setTenantId("default"); e.setPlanId(plan.getId()); e.setCourseId(course.getId()); e.setEngineerId(engineer.getId());
            e.setStatus(TrainingPlanService.ENROLLMENT_STARTED); e.setVersion(0); e.setPlannedCostSnapshot(BigDecimal.ZERO);
            enrollmentMapper.insert(e); f.enrollmentIds.add(e.getId());
        }
        return f;
    }

    private TrainingCourse course(String name) {
        TrainingCourse course = new TrainingCourse(); course.setTenantId("default"); course.setProvider("NF03");
        course.setName(name); course.setCostJpy(BigDecimal.ZERO); course.setActiveFlag(1); course.setVersion(0);
        courseMapper.insert(course); return course;
    }

    private void awaitLockWait(Future<?> worker, Long planId, AtomicLong requestingConnectionId,
                               AtomicLong blockingConnectionId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (hasTargetPlanRecordLockWait(planId, requestingConnectionId.get(), blockingConnectionId.get())) return;
            if (worker.isDone()) {
                try {
                    worker.get();
                    throw new AssertionError("後続workerがlock wait前に正常完了した。transactions=" + transactions());
                } catch (java.util.concurrent.ExecutionException failed) {
                    throw new AssertionError("後続workerがlock wait前に失敗した。原因=" + failed.getCause()
                            + "; transactions=" + transactions(), failed.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            Thread.yield();
        }
        throw new AssertionError("worker同士が対象plan PRIMARY RECORD lockで待機しなかった。planId=" + planId
                + ", requestingConnectionId=" + requestingConnectionId.get() + ", blockingConnectionId="
                + blockingConnectionId.get() + "; transactions=" + transactions() + "; lockWaits=" + lockWaits());
    }

    private boolean hasGrantedPlanRecordLock(Long planId, long connectionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks dl "
                + "JOIN performance_schema.threads th ON th.THREAD_ID=dl.THREAD_ID "
                + "WHERE dl.OBJECT_SCHEMA=DATABASE() AND dl.OBJECT_NAME='t_learning_plan' "
                + "AND dl.INDEX_NAME='PRIMARY' AND dl.LOCK_TYPE='RECORD' AND dl.LOCK_STATUS='GRANTED' "
                + "AND CAST(dl.LOCK_DATA AS UNSIGNED)=? AND th.PROCESSLIST_ID=?", Integer.class, planId, connectionId);
        return count != null && count > 0;
    }

    private boolean hasTargetPlanRecordLockWait(Long planId, long requestingConnectionId, long blockingConnectionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE "
                + "AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE "
                + "AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.threads rt ON rt.THREAD_ID=req.THREAD_ID "
                + "JOIN performance_schema.threads bt ON bt.THREAD_ID=blk.THREAD_ID "
                + "WHERE req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_learning_plan' "
                + "AND req.INDEX_NAME='PRIMARY' AND req.LOCK_TYPE='RECORD' "
                + "AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_learning_plan' "
                + "AND blk.INDEX_NAME='PRIMARY' AND blk.LOCK_TYPE='RECORD' "
                + "AND CAST(req.LOCK_DATA AS UNSIGNED)=? AND CAST(blk.LOCK_DATA AS UNSIGNED)=? "
                + "AND rt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?", Integer.class,
                planId, planId, requestingConnectionId, blockingConnectionId);
        return count != null && count > 0;
    }

    private long connectionId() {
        return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    private String locks() {
        return jdbc.query("SELECT CONCAT(object_name, ':', lock_type, ':', lock_mode, ':', lock_status, ':', "
                        + "COALESCE(index_name, 'NULL')) FROM performance_schema.data_locks", rs -> {
            java.util.List<String> states = new java.util.ArrayList<>();
            while (rs.next()) states.add(rs.getString(1));
            return states.toString();
        });
    }

    private String transactions() {
        return jdbc.query("SELECT CONCAT(trx_state, ':', trx_mysql_thread_id, ':', COALESCE(trx_query, 'NULL')) "
                        + "FROM information_schema.innodb_trx", rs -> {
            java.util.List<String> states = new java.util.ArrayList<>();
            while (rs.next()) states.add(rs.getString(1));
            return states.toString();
        });
    }

    private String lockWaits() {
        return jdbc.query("SELECT CONCAT(req.OBJECT_SCHEMA, '.', req.OBJECT_NAME, ':', req.INDEX_NAME, ':', "
                + "req.LOCK_TYPE, ':', req.LOCK_DATA, ':', rt.PROCESSLIST_ID, ' -> ', "
                + "blk.LOCK_TYPE, ':', blk.LOCK_DATA, ':', bt.PROCESSLIST_ID) "
                + "FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.threads rt ON rt.THREAD_ID=req.THREAD_ID "
                + "JOIN performance_schema.threads bt ON bt.THREAD_ID=blk.THREAD_ID", rs -> {
            java.util.List<String> states = new java.util.ArrayList<>();
            while (rs.next()) states.add(rs.getString(1));
            return states.toString();
        });
    }

    private void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private void tenant() { AccountingTenantContextHolder.setTenantId("default"); }
    private LearningPlan plan(Long id) { tenant(); return planMapper.selectById(id); }
    private TrainingEnrollment enrollment(Long id) { tenant(); return enrollmentMapper.selectById(id); }

    private static final class Fixture {
        private final Long planId;
        private final java.util.List<Long> enrollmentIds = new java.util.ArrayList<>();
        private Fixture(Long planId) { this.planId = planId; }
    }
}
