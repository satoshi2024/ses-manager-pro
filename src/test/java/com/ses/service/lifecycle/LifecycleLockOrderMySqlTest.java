package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.LifecycleTaskDep;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.AssetOffboardingWaiverMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleEventMapper;
import com.ses.mapper.LifecycleTaskDepMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetOffboardingService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.lifecycle.impl.LifecycleExceptionApprovalAdapter;
import com.ses.test.MySQLContainer;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * NF01 batch 0 (M0): 実MySQL 8上でライフサイクル更新の行ロック順「案件 → タスク（id昇順）」を検証する。
 *
 * <p>各シナリオは保持用transactionで特定行を固定し、performance_schemaで待機状態を確認してから解放する。
 * 旧実装（task+caseをjoinしたFOR UPDATE、タスク先行ロック、依存定義順の後続ロック、非ロック読取した全列書戻し）では
 * 「タスク行を保持したまま案件行を待つ」状態・1213 deadlock・昇格喪失・古い値での上書きのいずれかが観測される。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class LifecycleLockOrderMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_lifecycle_lock_order")
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

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final AtomicInteger TEMPLATE_VERSION = new AtomicInteger(700_000);
    private static final long WAIT_SECONDS = 30;

    @Autowired private LifecycleCaseService caseService;
    @Autowired private LifecycleTaskService taskService;
    @Autowired private LifecycleExceptionApprovalAdapter approvalAdapter;
    @Autowired private AssetOffboardingService assetOffboardingService;
    @Autowired private ResignationGateChecker resignationGateChecker;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private LifecycleTemplateMapper templateMapper;
    @Autowired private LifecycleCaseMapper caseMapper;
    @Autowired private LifecycleTaskMapper taskMapper;
    @Autowired private LifecycleTaskDepMapper taskDepMapper;
    @Autowired private LifecycleEventMapper eventMapper;
    @Autowired private ApprovalRequestMapper approvalRequestMapper;
    @Autowired private ApprovalActionMapper approvalActionMapper;
    @Autowired private AssetOffboardingWaiverMapper waiverMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void 中止が案件を保持中のタスク完了はタスク行を握らず案件待ちになり_deadlockせず400で拒否される() throws Exception {
        Fixture f = fixture("LOCK_TASK_B", false);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        AtomicLong cancelConnection = new AtomicLong();
        AtomicLong completeConnection = new AtomicLong();
        try {
            Future<Throwable> holder = holdRow(pool, "t_lifecycle_task", f.taskA(), holderConnection, held, release);
            awaitLatch(held, "保持transactionがタスクAをロックする");
            requireSingleHeldRow(holderConnection, "t_lifecycle_task", f.taskA());
            Future<Throwable> cancel = worker(pool, f, cancelConnection, null, null,
                    () -> caseService.cancelCase(f.caseId(), f.admin().getId(), "ロック順検証の中止"));
            awaitRecordLockWait(cancel, "t_lifecycle_task", f.taskA(), cancelConnection, holderConnection);
            Future<Throwable> complete = worker(pool, f, completeConnection, null, null,
                    () -> taskService.completeTask(f.taskB(), f.admin().getId(),
                            CompleteLifecycleTaskCommand.builder().completionComment("並行完了").build()));
            awaitRecordLockWait(complete, "t_lifecycle_case", f.caseId(), completeConnection, cancelConnection);
            assertEquals(0, grantedRecordLocks("t_lifecycle_task", completeConnection.get()),
                    "タスク完了は案件行を待つ間タスク行を保持しないこと（案件 → タスク）。locks=" + locks());

            release.countDown();
            assertSucceeded("保持transaction", holder.get(WAIT_SECONDS, TimeUnit.SECONDS));
            Throwable cancelOutcome = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable completeOutcome = complete.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNoLockFailure("cancelCase", cancelOutcome);
            assertNoLockFailure("completeTask", completeOutcome);
            assertSucceeded("cancelCase", cancelOutcome);
            assertBusinessRejection("completeTask", completeOutcome, 400, "error.lifecycle.caseNotActive");

            assertEquals("CANCELLED", caseMapper.selectById(f.caseId()).getStatus());
            assertEquals("CANCELLED", taskMapper.selectById(f.taskA()).getStatus());
            assertEquals("CANCELLED", taskMapper.selectById(f.taskB()).getStatus());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void タスク完了が先行した場合も中止はタスク行を握らず案件待ちになり両方確定する() throws Exception {
        Fixture f = fixture("LOCK_TASK_B", false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong completeConnection = new AtomicLong();
        AtomicLong cancelConnection = new AtomicLong();
        try {
            Future<Throwable> complete = worker(pool, f, completeConnection, completed, release,
                    () -> taskService.completeTask(f.taskB(), f.admin().getId(),
                            CompleteLifecycleTaskCommand.builder().completionComment("先行完了").build()));
            awaitLatch(completed, "タスク完了transactionが案件・タスクのロックを保持する");
            assertTrue(grantedRecordLocks("t_lifecycle_case", completeConnection.get()) > 0, "locks=" + locks());
            assertTrue(grantedRecordLocks("t_lifecycle_task", completeConnection.get()) > 0, "locks=" + locks());
            Future<Throwable> cancel = worker(pool, f, cancelConnection, null, null,
                    () -> caseService.cancelCase(f.caseId(), f.admin().getId(), "完了後の中止"));
            awaitRecordLockWait(cancel, "t_lifecycle_case", f.caseId(), cancelConnection, completeConnection);
            assertEquals(0, grantedRecordLocks("t_lifecycle_task", cancelConnection.get()),
                    "中止は案件行を待つ間タスク行を保持しないこと。locks=" + locks());

            release.countDown();
            Throwable completeOutcome = complete.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable cancelOutcome = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNoLockFailure("completeTask", completeOutcome);
            assertNoLockFailure("cancelCase", cancelOutcome);
            assertSucceeded("completeTask", completeOutcome);
            assertSucceeded("cancelCase", cancelOutcome);

            assertEquals("CANCELLED", caseMapper.selectById(f.caseId()).getStatus());
            assertEquals("CANCELLED", taskMapper.selectById(f.taskA()).getStatus());
            assertEquals("COMPLETED", taskMapper.selectById(f.taskB()).getStatus());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 担当変更と中止が同じ案件を待って競合しても中止後に非終端タスクを残さない() throws Exception {
        Fixture f = fixture("LOCK_TASK_B", false);
        SysUser assignee = tenantUser(f, "HR");
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        AtomicLong reassignConnection = new AtomicLong();
        AtomicLong cancelConnection = new AtomicLong();
        try {
            Future<Throwable> holder = holdRow(pool, "t_lifecycle_case", f.caseId(), holderConnection, held, release);
            awaitLatch(held, "保持transactionが案件をロックする");
            requireSingleHeldRow(holderConnection, "t_lifecycle_case", f.caseId());
            Future<Throwable> reassign = worker(pool, f, reassignConnection, null, null,
                    () -> taskService.reassignTask(f.taskA(), assignee.getId(), f.admin().getId(), "並行担当変更"));
            awaitRecordLockWait(reassign, "t_lifecycle_case", f.caseId(), reassignConnection, holderConnection);
            assertEquals(0, grantedRecordLocks("t_lifecycle_task", reassignConnection.get()),
                    "担当変更は案件行を待つ間タスク行を保持しないこと。locks=" + locks());
            Future<Throwable> cancel = worker(pool, f, cancelConnection, null, null,
                    () -> caseService.cancelCase(f.caseId(), f.admin().getId(), "担当変更と競合する中止"));
            awaitRecordLockWait(cancel, "t_lifecycle_case", f.caseId(), cancelConnection, holderConnection);

            release.countDown();
            assertSucceeded("保持transaction", holder.get(WAIT_SECONDS, TimeUnit.SECONDS));
            Throwable reassignOutcome = reassign.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable cancelOutcome = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNoLockFailure("reassignTask", reassignOutcome);
            assertNoLockFailure("cancelCase", cancelOutcome);
            assertSucceeded("cancelCase", cancelOutcome);
            if (reassignOutcome != null) {
                assertBusinessRejection("reassignTask", reassignOutcome, 400, "error.lifecycle.caseNotActive");
            }

            // 旧実装では中止が古いsnapshotのversionでタスクCASに失敗し、0件を無視して IN_PROGRESS が残り得た（L4）。
            assertEquals("CANCELLED", caseMapper.selectById(f.caseId()).getStatus());
            assertEquals("CANCELLED", taskMapper.selectById(f.taskA()).getStatus());
            assertEquals("CANCELLED", taskMapper.selectById(f.taskB()).getStatus());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 承認確定の版確認と免除は中止と同じ案件先行順でロックしdeadlockしない() throws Exception {
        Fixture f = fixture("RESIGN_ASSET_RETURN", false);
        Long requestId = approvedException(f, f.taskB());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        AtomicLong cancelConnection = new AtomicLong();
        AtomicLong approvalConnection = new AtomicLong();
        try {
            Future<Throwable> holder = holdRow(pool, "t_lifecycle_task", f.taskA(), holderConnection, held, release);
            awaitLatch(held, "保持transactionがタスクAをロックする");
            requireSingleHeldRow(holderConnection, "t_lifecycle_task", f.taskA());
            Future<Throwable> cancel = worker(pool, f, cancelConnection, null, null,
                    () -> caseService.cancelCase(f.caseId(), f.admin().getId(), "承認確定と競合する中止"));
            awaitRecordLockWait(cancel, "t_lifecycle_task", f.taskA(), cancelConnection, holderConnection);
            Future<Throwable> approval = worker(pool, f, approvalConnection, null, null,
                    () -> applyApprovedLikeEngine(f, requestId));
            awaitRecordLockWait(approval, "t_lifecycle_case", f.caseId(), approvalConnection, cancelConnection);
            assertEquals(0, grantedRecordLocks("t_lifecycle_task", approvalConnection.get()),
                    "承認確定（currentVersion→applyApproved）は案件行を待つ間タスク行を保持しないこと。locks=" + locks());

            release.countDown();
            assertSucceeded("保持transaction", holder.get(WAIT_SECONDS, TimeUnit.SECONDS));
            Throwable cancelOutcome = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable approvalOutcome = approval.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNoLockFailure("cancelCase", cancelOutcome);
            assertNoLockFailure("applyApproved", approvalOutcome);
            assertSucceeded("cancelCase", cancelOutcome);
            assertBusinessRejection("applyApproved", approvalOutcome, 400, "error.lifecycle.caseNotActive");

            assertEquals("CANCELLED", caseMapper.selectById(f.caseId()).getStatus());
            assertEquals("CANCELLED", taskMapper.selectById(f.taskB()).getStatus());
            assertNull(waiverMapper.selectByApprovalRequestId(requestId), "rollbackされた免除は台帳に残らないこと");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 承認確定が先行した場合は免除台帳まで確定し後続の中止は案件待ちの後に残タスクだけを中止する() throws Exception {
        Fixture f = fixture("RESIGN_ASSET_RETURN", false);
        Long requestId = approvedException(f, f.taskB());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch applied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong approvalConnection = new AtomicLong();
        AtomicLong cancelConnection = new AtomicLong();
        try {
            Future<Throwable> approval = worker(pool, f, approvalConnection, applied, release,
                    () -> applyApprovedLikeEngine(f, requestId));
            awaitLatch(applied, "承認確定transactionが免除を適用してロックを保持する");
            Future<Throwable> cancel = worker(pool, f, cancelConnection, null, null,
                    () -> caseService.cancelCase(f.caseId(), f.admin().getId(), "免除後の中止"));
            awaitRecordLockWait(cancel, "t_lifecycle_case", f.caseId(), cancelConnection, approvalConnection);
            assertEquals(0, grantedRecordLocks("t_lifecycle_task", cancelConnection.get()), "locks=" + locks());

            release.countDown();
            Throwable approvalOutcome = approval.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable cancelOutcome = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNoLockFailure("applyApproved", approvalOutcome);
            assertNoLockFailure("cancelCase", cancelOutcome);
            assertSucceeded("applyApproved", approvalOutcome);
            assertSucceeded("cancelCase", cancelOutcome);

            assertEquals("WAIVED", taskMapper.selectById(f.taskB()).getStatus());
            assertEquals("CANCELLED", taskMapper.selectById(f.taskA()).getStatus());
            assertEquals("CANCELLED", caseMapper.selectById(f.caseId()).getStatus());
            assertNotNull(waiverMapper.selectByApprovalRequestId(requestId));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 退社例外免除の台帳追記は案件先行でロックし中止とdeadlockしない() throws Exception {
        Fixture f = fixture("RESIGN_ASSET_RETURN", false);
        Long requestId = approvedException(f, f.taskB());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        AtomicLong cancelConnection = new AtomicLong();
        AtomicLong waiverConnection = new AtomicLong();
        try {
            Future<Throwable> holder = holdRow(pool, "t_lifecycle_task", f.taskA(), holderConnection, held, release);
            awaitLatch(held, "保持transactionがタスクAをロックする");
            requireSingleHeldRow(holderConnection, "t_lifecycle_task", f.taskA());
            Future<Throwable> cancel = worker(pool, f, cancelConnection, null, null,
                    () -> caseService.cancelCase(f.caseId(), f.admin().getId(), "台帳追記と競合する中止"));
            awaitRecordLockWait(cancel, "t_lifecycle_task", f.taskA(), cancelConnection, holderConnection);
            Future<Throwable> waiver = worker(pool, f, waiverConnection, null, null,
                    () -> assetOffboardingService.approveOffboardingWaiver(f.engineer().getId(), f.caseId(), f.taskB(),
                            "ロック順検証の免除", requestId, f.admin().getId()));
            awaitRecordLockWait(waiver, "t_lifecycle_case", f.caseId(), waiverConnection, cancelConnection);
            assertEquals(0, grantedRecordLocks("t_lifecycle_task", waiverConnection.get()),
                    "approveOffboardingWaiverは案件行を待つ間タスク行を保持しないこと。locks=" + locks());

            release.countDown();
            assertSucceeded("保持transaction", holder.get(WAIT_SECONDS, TimeUnit.SECONDS));
            Throwable cancelOutcome = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable waiverOutcome = waiver.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNoLockFailure("cancelCase", cancelOutcome);
            assertNoLockFailure("approveOffboardingWaiver", waiverOutcome);
            assertSucceeded("cancelCase", cancelOutcome);
            // 中止済み案件への台帳追記可否は本batchの対象外（現行仕様を変えない）。lock失敗でないことだけを要求する。
            assertTrue(waiverOutcome == null || waiverOutcome instanceof BusinessException,
                    "approveOffboardingWaiverは成功または業務例外で終わること: " + waiverOutcome);
            assertEquals("CANCELLED", caseMapper.selectById(f.caseId()).getStatus());
            assertEquals("CANCELLED", taskMapper.selectById(f.taskA()).getStatus());
            assertEquals("CANCELLED", taskMapper.selectById(f.taskB()).getStatus());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 同一案件の2タスク並行完了は案件で直列化され共通後続の自動昇格を失わない() throws Exception {
        Fixture f = fixture("LOCK_TASK_B", true);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        AtomicLong firstConnection = new AtomicLong();
        AtomicLong secondConnection = new AtomicLong();
        try {
            Future<Throwable> holder = holdRow(pool, "t_lifecycle_case", f.caseId(), holderConnection, held, release);
            awaitLatch(held, "保持transactionが案件をロックする");
            requireSingleHeldRow(holderConnection, "t_lifecycle_case", f.caseId());
            Future<Throwable> first = worker(pool, f, firstConnection, null, null,
                    () -> taskService.completeTask(f.taskA(), f.admin().getId(),
                            CompleteLifecycleTaskCommand.builder().completionComment("A完了").build()));
            Future<Throwable> second = worker(pool, f, secondConnection, null, null,
                    () -> taskService.completeTask(f.taskB(), f.admin().getId(),
                            CompleteLifecycleTaskCommand.builder().completionComment("B完了").build()));
            awaitRecordLockWait(first, "t_lifecycle_case", f.caseId(), firstConnection, holderConnection);
            awaitRecordLockWait(second, "t_lifecycle_case", f.caseId(), secondConnection, holderConnection);
            assertEquals(0, grantedRecordLocks("t_lifecycle_task", firstConnection.get()), "locks=" + locks());
            assertEquals(0, grantedRecordLocks("t_lifecycle_task", secondConnection.get()), "locks=" + locks());

            release.countDown();
            assertSucceeded("保持transaction", holder.get(WAIT_SECONDS, TimeUnit.SECONDS));
            Throwable firstOutcome = first.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable secondOutcome = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNoLockFailure("completeTask(A)", firstOutcome);
            assertNoLockFailure("completeTask(B)", secondOutcome);
            assertSucceeded("completeTask(A)", firstOutcome);
            assertSucceeded("completeTask(B)", secondOutcome);

            assertEquals("COMPLETED", taskMapper.selectById(f.taskA()).getStatus());
            assertEquals("COMPLETED", taskMapper.selectById(f.taskB()).getStatus());
            assertEquals("IN_PROGRESS", taskMapper.selectById(f.taskC()).getStatus(),
                    "後で確定した完了が先行完了をlocking readで観測し、共通後続を昇格すること");
            long promotions = eventMapper.selectByCaseId(f.caseId()).stream()
                    .filter(event -> f.taskC().equals(event.getTaskId()))
                    .filter(event -> "TASK_STARTED".equals(event.getEventType()))
                    .count();
            assertEquals(1, promotions);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 退社時のユーザー停止は行ロック後のstatus列更新で並行確定した他列を上書きしない() throws Exception {
        Fixture f = fixture("LOCK_TASK_B", false);
        SysUser linked = linkedUser(f);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        AtomicLong deactivateConnection = new AtomicLong();
        String renamed = "並行更新後の氏名";
        try {
            Future<Throwable> holder = pool.submit(() -> {
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        holderConnection.set(currentConnectionId());
                        jdbc.update("UPDATE sys_user SET real_name = ? WHERE id = ?", renamed, linked.getId());
                        held.countDown();
                        awaitLatch(release, "保持transactionの解放");
                    });
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            awaitLatch(held, "保持transactionが連携ユーザー行を更新してロックする");
            requireSingleHeldRow(holderConnection, "sys_user", linked.getId());
            Future<Throwable> deactivate = worker(pool, f, deactivateConnection, null, null,
                    () -> resignationGateChecker.executeAutomaticGateActions(caseMapper.selectById(f.caseId()), f.engineer()));
            awaitRecordLockWait(deactivate, "sys_user", linked.getId(), deactivateConnection, holderConnection);

            release.countDown();
            assertSucceeded("保持transaction", holder.get(WAIT_SECONDS, TimeUnit.SECONDS));
            Throwable outcome = deactivate.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNoLockFailure("executeAutomaticGateActions", outcome);
            assertSucceeded("executeAutomaticGateActions", outcome);

            SysUser after = sysUserMapper.selectById(linked.getId());
            assertEquals(0, after.getStatus());
            assertEquals(renamed, after.getRealName(), "非ロック読取した古い値で他列を書き戻さないこと");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ===== fixture =====

    private Fixture fixture(String taskBCode, boolean withSuccessor) {
        long seq = SEQUENCE.incrementAndGet();
        String tenant = "lc-lock-" + seq;
        SysUser admin = SysUser.builder().tenantId(tenant).username("lc_lock_admin_" + seq).password("pass")
                .realName("ロック順管理者").role("管理者").status(1).build();
        sysUserMapper.insert(admin);
        Engineer engineer = Engineer.builder().tenantId(tenant).fullName("ロック順要員" + seq)
                .status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        LifecycleTemplate template = LifecycleTemplate.builder().templateType("RESIGNATION").name("ロック順検証" + seq)
                .versionNo(TEMPLATE_VERSION.incrementAndGet()).status("ACTIVE").validFrom(LocalDate.now().minusDays(1))
                .createdBy(admin.getId()).updatedBy(admin.getId()).build();
        templateMapper.insert(template);
        LifecycleCase lcCase = LifecycleCase.builder().tenantId(tenant).caseNo("LC-LOCK-" + seq)
                .lifecycleType("RESIGNATION").engineerId(engineer.getId()).templateId(template.getId())
                .templateVersion(template.getVersionNo()).anchorDate(LocalDate.now()).status("ACTIVE")
                .title("ロック順検証案件").applicantUserId(admin.getId()).engineerSnapshotJson("{}")
                .createdBy(admin.getId()).updatedBy(admin.getId()).build();
        caseMapper.insert(lcCase);
        Long taskA = task(lcCase, admin, "LOCK_TASK_A", "IN_PROGRESS");
        Long taskB = task(lcCase, admin, taskBCode, "IN_PROGRESS");
        Long taskC = null;
        if (withSuccessor) {
            taskC = task(lcCase, admin, "LOCK_TASK_C", "PENDING");
            dependency(lcCase, taskA, taskC);
            dependency(lcCase, taskB, taskC);
        }
        assertTrue(taskA < taskB, "タスクAのidはタスクBより小さいこと");
        return new Fixture(tenant, admin, engineer, lcCase.getId(), taskA, taskB, taskC);
    }

    private Long task(LifecycleCase lcCase, SysUser admin, String code, String status) {
        LifecycleTask task = LifecycleTask.builder().caseId(lcCase.getId()).taskCode(code).taskName(code)
                .dueDate(LocalDate.now().plusDays(7)).assigneeRole("HR").isMandatory(1).isBlocking(1)
                .evidenceType("NONE").isEngineerVisible(1).status(status)
                .createdBy(admin.getId()).updatedBy(admin.getId()).build();
        taskMapper.insert(task);
        return task.getId();
    }

    private void dependency(LifecycleCase lcCase, Long predecessorId, Long successorId) {
        taskDepMapper.insert(LifecycleTaskDep.builder().caseId(lcCase.getId())
                .predecessorTaskId(predecessorId).successorTaskId(successorId).build());
    }

    private Long approvedException(Fixture f, Long taskId) {
        long seq = SEQUENCE.incrementAndGet();
        ApprovalRequest request = ApprovalRequest.builder().tenantId(f.tenant()).requestNo("AR-LOCK-" + seq)
                .requestType("LIFECYCLE_EXCEPTION").targetType("LIFECYCLE_TASK").targetId(taskId).targetVersion(0L)
                .applicantId(f.admin().getId())
                .payloadJson("{\"reason\":\"ロック順検証の承認済み例外\",\"riskOwner\":\"HR\",\"remedyDeadline\":\""
                        + LocalDate.now().plusMonths(1) + "\"}")
                .routeSnapshotJson("[]").status("APPROVED").currentStep(1).roundNo(1).version(1).build();
        approvalRequestMapper.insert(request);
        approvalActionMapper.insert(ApprovalAction.builder().tenantId(f.tenant()).requestId(request.getId())
                .roundNo(1).stepNo(1).slotIndex(0).approverUserId(f.admin().getId())
                .approverSlotUserId(f.admin().getId()).action("APPROVE").comment("承認")
                .actedAt(LocalDateTime.now()).build());
        return request.getId();
    }

    private SysUser tenantUser(Fixture f, String role) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(f.tenant()).username("lc_lock_user_" + seq).password("pass")
                .realName("ロック順担当者").role(role).status(1).build();
        sysUserMapper.insert(user);
        return user;
    }

    private SysUser linkedUser(Fixture f) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(f.tenant()).username("lc_lock_engineer_" + seq).password("pass")
                .realName("退社予定要員").role("要員").status(1).build();
        sysUserMapper.insert(user);
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(f.tenant());
        link.setEngineerId(f.engineer().getId());
        link.setSysUserId(user.getId());
        engineerAccountLinkMapper.insert(link);
        return user;
    }

    /**
     * ApprovalEngineServiceImpl.approve と同じロック順（申請行ロック → currentVersion → applyApproved）で適用する。
     *
     * <p>本helperが検証するのはロック順だけである。実engineは currentVersion の戻り値を申請時のtarget_versionと比較し、
     * 先に中止が確定してtask versionが進んでいれば CONFLICT として正常終了するが、ここではversion比較を省くため、
     * 中止先行時の結果は waiveTask の 400 caseNotActive になる。業務上の最終応答（CONFLICT）の証拠としては扱わない。</p>
     */
    private void applyApprovedLikeEngine(Fixture f, Long requestId) {
        ApprovalRequest request = approvalRequestMapper.selectByIdForUpdate(requestId, f.tenant());
        assertNotNull(request);
        approvalAdapter.currentVersion(request.getTargetId());
        approvalAdapter.applyApproved(request);
    }

    // ===== concurrency helpers =====

    private Future<Throwable> holdRow(ExecutorService pool, String table, Long id, AtomicLong connectionId,
                                      CountDownLatch held, CountDownLatch release) {
        String sql = switch (table) {
            case "t_lifecycle_task" -> "SELECT id FROM t_lifecycle_task WHERE id = ? FOR UPDATE";
            case "t_lifecycle_case" -> "SELECT id FROM t_lifecycle_case WHERE id = ? FOR UPDATE";
            default -> throw new IllegalArgumentException(table);
        };
        return pool.submit(() -> {
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    connectionId.set(currentConnectionId());
                    jdbc.queryForList(sql, id);
                    held.countDown();
                    awaitLatch(release, "保持transactionの解放");
                });
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });
    }

    private Future<Throwable> worker(ExecutorService pool, Fixture f, AtomicLong connectionId,
                                     CountDownLatch done, CountDownLatch release, Runnable action) {
        return pool.submit(() -> {
            TenantTestSecurity.bindAs(f.admin().getId(), f.admin().getUsername(), f.tenant(), "管理者");
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    connectionId.set(currentConnectionId());
                    action.run();
                    if (done != null) {
                        done.countDown();
                        awaitLatch(release, "workerの解放");
                    }
                });
                return null;
            } catch (Throwable failure) {
                return failure;
            } finally {
                SecurityContextHolder.clearContext();
                TestSecurityContextHolder.clearContext();
                AccountingTenantContextHolder.clear();
            }
        });
    }

    private long currentConnectionId() {
        Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        return id == null ? 0L : id;
    }

    /** 排他record lockのLOCK_MODE（gap専用・insert intentionは含めない）。 */
    private static final Set<String> EXCLUSIVE_RECORD_MODES = Set.of("X", "X,REC_NOT_GAP");

    /**
     * requesterが対象表PRIMARYの行ロックをblockerに待たされていることを確認する。
     *
     * <p>{@code performance_schema.data_locks.LOCK_DATA} は対象pageをlatch待ちなしで参照できた場合だけ鍵値を返し、
     * それ以外はNULLになる（ImportMetadataRealMySqlTestで実際に観測済み）。鍵値の一致を必須にすると、実際には待機して
     * いるのに観測できず期限切れになる非決定性が残るため、鍵値に依存せず次の組合せで「対象行での待機」を確定する。</p>
     * <ol>
     *   <li>構造：requesterのRECORD/WAITING排他lockとblockerのRECORD/GRANTED排他lockが、同じ表のPRIMARY上で
     *       {@code data_lock_waits} の1辺として結ばれ、両端のPROCESSLIST_IDが期待接続と一致し互いに異なる。</li>
     *   <li>行の同一性（requester基準）：本テストのrequesterが待つ文はすべて主キー一点のlocking read/UPDATEであり、
     *       InnoDBのtransactionが同時に待機するlockは1件だけなので、その待機lockは期待行のものである。</li>
     *   <li>行の同一性（blocker基準）：保持用transactionは {@link #requireSingleHeldRow} で当該表PRIMARYの排他record lockを
     *       ちょうど1件（期待行）だけ持つことを事前確認する。workerがblockerの場合も、当該表で行ロックするのは対象行だけ。</li>
     *   <li>鍵値：LOCK_DATAが見えている側は期待主キーと一致しなければならない（別行の待機を証拠にしない）。NULLは不可視として扱う。</li>
     * </ol>
     */
    private void awaitRecordLockWait(Future<Throwable> worker, String table, Long rowId, AtomicLong requesting,
                                     AtomicLong blocking) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            long requester = requesting.get();
            long blocker = blocking.get();
            if (requester > 0 && blocker > 0 && requester != blocker
                    && hasRecordLockWait(table, rowId, requester, blocker)) {
                return;
            }
            if (worker.isDone()) {
                Throwable outcome = worker.get();
                throw new AssertionError(table + "#" + rowId + " の行ロック待機前にworkerが終了した。outcome="
                        + outcome + "; locks=" + locks(), outcome);
            }
            Thread.sleep(20);
        }
        throw new AssertionError(table + "#" + rowId + " の行ロック待機を観測できなかった。requester="
                + requesting.get() + ", blocker=" + blocking.get() + "; edges=" + describeWaitEdges(table, requesting.get())
                + "; locks=" + locks() + "; waits=" + lockWaits());
    }

    private boolean hasRecordLockWait(String table, Long rowId, long requester, long blocker) {
        for (WaitEdge edge : waitEdges(table, requester)) {
            if (edge.blocker() == blocker && edge.exclusivePrimaryRecordWait() && edge.visibleKeysMatch(rowId)) {
                return true;
            }
        }
        return false;
    }

    /** requesterの、指定表上の待機辺（REQUESTING/BLOCKING threadで結ぶ。LOCK_DATAでは結合しない）。 */
    private List<WaitEdge> waitEdges(String table, long requester) {
        return jdbc.query("SELECT wt.PROCESSLIST_ID, bt.PROCESSLIST_ID, "
                        + "req.INDEX_NAME, req.LOCK_TYPE, req.LOCK_MODE, req.LOCK_STATUS, req.LOCK_DATA, "
                        + "blk.INDEX_NAME, blk.LOCK_TYPE, blk.LOCK_MODE, blk.LOCK_STATUS, blk.LOCK_DATA "
                        + "FROM performance_schema.data_lock_waits w "
                        + "JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID "
                        + "JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID "
                        + "JOIN performance_schema.data_locks req "
                        + "ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                        + "JOIN performance_schema.data_locks blk "
                        + "ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                        + "WHERE wt.PROCESSLIST_ID=? AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=? "
                        + "AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=?",
                (rs, row) -> new WaitEdge(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12)),
                requester, table, table);
    }

    private String describeWaitEdges(String table, long requester) {
        try {
            return waitEdges(table, requester).stream().map(WaitEdge::describe).toList().toString();
        } catch (RuntimeException failure) {
            return "待機辺の取得に失敗: " + failure.getClass().getSimpleName();
        }
    }

    /**
     * 保持用transactionが当該表PRIMARYの排他record lockをちょうど1件だけ持ち、鍵値が見える場合は期待行であることを確認する。
     * 保持用transactionは主キー一点の文だけを実行するため、1件であればそれが期待行のlockである。
     */
    private void requireSingleHeldRow(AtomicLong holder, String table, Long rowId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<String> held = List.of();
        while (System.nanoTime() < deadline) {
            held = heldExclusivePrimaryRecordKeys(table, holder.get());
            if (held.size() == 1) {
                if (!visibleKeyMatches(held.get(0), rowId)) {
                    throw new AssertionError("保持transactionが期待と異なる行をlockしている: " + table + " key=" + held.get(0));
                }
                return;
            }
            if (held.size() > 1) {
                break;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("保持transactionが" + table + "/PRIMARYの排他record lockをちょうど1件保持していることを"
                + "確認できなかった: keys=" + held + "; locks=" + locks());
    }

    private List<String> heldExclusivePrimaryRecordKeys(String table, long connectionId) {
        return jdbc.query("SELECT dl.LOCK_MODE, dl.LOCK_DATA FROM performance_schema.data_locks dl "
                        + "JOIN performance_schema.threads th ON th.THREAD_ID=dl.THREAD_ID "
                        + "WHERE th.PROCESSLIST_ID=? AND dl.OBJECT_SCHEMA=DATABASE() AND dl.OBJECT_NAME=? "
                        + "AND dl.INDEX_NAME='PRIMARY' AND dl.LOCK_TYPE='RECORD' AND dl.LOCK_STATUS='GRANTED'",
                        (rs, row) -> new String[] {rs.getString(1), rs.getString(2)}, connectionId, table).stream()
                .filter(lock -> EXCLUSIVE_RECORD_MODES.contains(lock[0]))
                .map(lock -> lock[1])
                .collect(java.util.stream.Collectors.toList());
    }

    /** LOCK_DATAが見えている場合だけ期待主キーとの一致を要求する（NULLは不可視として合格扱い）。 */
    private static boolean visibleKeyMatches(String lockData, long rowId) {
        if (lockData == null) {
            return true;
        }
        String value = lockData.trim();
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        }
        return Long.toString(rowId).equals(value);
    }

    /** {@code data_lock_waits} の1辺（requesterのWAITING lock → blockerのlock）。 */
    private record WaitEdge(long requester, long blocker,
                            String requesterIndex, String requesterType, String requesterMode,
                            String requesterStatus, String requesterData,
                            String blockerIndex, String blockerType, String blockerMode,
                            String blockerStatus, String blockerData) {
        boolean exclusivePrimaryRecordWait() {
            return requester != blocker
                    && "PRIMARY".equals(requesterIndex) && "PRIMARY".equals(blockerIndex)
                    && "RECORD".equals(requesterType) && "WAITING".equals(requesterStatus)
                    && EXCLUSIVE_RECORD_MODES.contains(requesterMode)
                    && "RECORD".equals(blockerType) && "GRANTED".equals(blockerStatus)
                    && EXCLUSIVE_RECORD_MODES.contains(blockerMode);
        }

        boolean visibleKeysMatch(long rowId) {
            return visibleKeyMatches(requesterData, rowId) && visibleKeyMatches(blockerData, rowId);
        }

        String describe() {
            return "requester=" + requester + " " + requesterIndex + "," + requesterType + "," + requesterMode + ","
                    + requesterStatus + ",key=" + requesterData + " -> blocker=" + blocker + " " + blockerIndex + ","
                    + blockerType + "," + blockerMode + "," + blockerStatus + ",key=" + blockerData;
        }
    }

    private int grantedRecordLocks(String table, long connectionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks dl "
                        + "JOIN performance_schema.threads th ON th.THREAD_ID=dl.THREAD_ID "
                        + "WHERE dl.OBJECT_SCHEMA=DATABASE() AND dl.OBJECT_NAME=? AND dl.LOCK_TYPE='RECORD' "
                        + "AND dl.LOCK_STATUS='GRANTED' AND th.PROCESSLIST_ID=?",
                Integer.class, table, connectionId);
        return count == null ? 0 : count;
    }

    private String locks() {
        return jdbc.query("SELECT CONCAT(th.PROCESSLIST_ID, ':', dl.OBJECT_NAME, ':', dl.LOCK_TYPE, ':', dl.LOCK_MODE, ':', "
                + "dl.LOCK_STATUS, ':', COALESCE(dl.INDEX_NAME, 'NULL'), ':', COALESCE(dl.LOCK_DATA, 'NULL')) "
                + "FROM performance_schema.data_locks dl JOIN performance_schema.threads th ON th.THREAD_ID=dl.THREAD_ID "
                + "WHERE dl.OBJECT_SCHEMA=DATABASE()", rs -> {
            java.util.List<String> states = new java.util.ArrayList<>();
            while (rs.next()) states.add(rs.getString(1));
            return states.toString();
        });
    }

    private String lockWaits() {
        return jdbc.query("SELECT CONCAT(rt.PROCESSLIST_ID, ' waits ', req.OBJECT_NAME, ':', COALESCE(req.LOCK_DATA, 'NULL'), "
                + "' held by ', bt.PROCESSLIST_ID) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.threads rt ON rt.THREAD_ID=req.THREAD_ID "
                + "JOIN performance_schema.threads bt ON bt.THREAD_ID=blk.THREAD_ID", rs -> {
            java.util.List<String> states = new java.util.ArrayList<>();
            while (rs.next()) states.add(rs.getString(1));
            return states.toString();
        });
    }

    private static void awaitLatch(CountDownLatch latch, String label) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError(label + " がtimeoutした");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void assertSucceeded(String label, Throwable outcome) {
        if (outcome != null) {
            throw new AssertionError(label + " が失敗した: " + outcome, outcome);
        }
    }

    private static void assertNoLockFailure(String label, Throwable outcome) {
        for (Throwable cause = outcome; cause != null; cause = cause.getCause()) {
            if (cause instanceof PessimisticLockingFailureException) {
                fail(label + " がlock失敗で終了した（deadlock/lock wait timeout）: " + outcome);
            }
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)) {
                fail(label + " がMySQL " + sql.getErrorCode() + " で終了した: " + outcome);
            }
        }
    }

    private static void assertBusinessRejection(String label, Throwable outcome, int code, String messageKey) {
        assertTrue(outcome instanceof BusinessException, label + " は業務例外で拒否されること: " + outcome);
        BusinessException rejected = (BusinessException) outcome;
        assertEquals(code, rejected.getCode(), label);
        assertEquals(messageKey, rejected.getMessageKey(), label);
    }

    private record Fixture(String tenant, SysUser admin, Engineer engineer, Long caseId,
                           Long taskA, Long taskB, Long taskC) {
    }
}
