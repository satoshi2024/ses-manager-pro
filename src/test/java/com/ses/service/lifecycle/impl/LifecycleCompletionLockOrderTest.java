package com.ses.service.lifecycle.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleEvent;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleEventMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.ExternalAccountRevocationOutbox;
import com.ses.service.lifecycle.LifecycleCompletionLockPlanner;
import com.ses.service.lifecycle.LifecycleNotificationService;
import com.ses.service.lifecycle.LifecycleScopeService;
import com.ses.service.lifecycle.ResignationGateChecker;
import com.ses.service.lifecycle.evidence.LifecycleLockExecutor;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.LockKey;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Rank;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * U8（NF01細粒度epoch計画 §3.4「completeCase」・§3.5・§4.2・§6.2、W3-16b）: 退社完了が、lock前の判定 →
 * 1つのplanの取得（連携ユーザーX先行）→ lock後の再discovery → 取得済みの行への書込み → 葉 の順に処理し、依存先の変化を
 * 書込み前に409で止めることをDB無しで固定する（実MySQLでの待ちと1213が出ないことは ResignationCompletionLockOrderMySqlTest）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LifecycleCompletionLockOrderTest {

    private static final String TENANT = "tenant-a";
    private static final Long OPERATOR_ID = 5L;
    private static final Long LINKED_USER_ID = 3L;
    private static final Long CASE_ID = 10L;
    private static final Long ENGINEER_ID = 20L;

    @Mock private LifecycleCaseMapper caseMapper;
    @Mock private LifecycleTaskMapper taskMapper;
    @Mock private LifecycleEventMapper eventMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private LifecycleScopeService scopeService;
    @Mock private ResignationGateChecker resignationGateChecker;
    @Mock private ExternalAccountRevocationOutbox revocationOutbox;
    @Mock private LifecycleNotificationService notificationService;
    @Mock private LifecycleCompletionLockPlanner completionLockPlanner;
    @Mock private LifecycleLockExecutor lockExecutor;
    @Mock private AuthorizationEpochFence authorizationEpochFence;

    @InjectMocks
    private LifecycleCaseServiceImpl service;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 退社完了は1つのplanを1回だけ取りlock後に再discoveryしてから取得済みの行を書きsession失効を最後に行う() {
        LifecycleCase discovered = resignation("ACTIVE");
        LifecycleCase locked = resignation("ACTIVE");
        Engineer engineer = stubResignation(discovered, locked);
        LifecycleLockPlan plan = resignationPlan();
        when(completionLockPlanner.plan(any(LifecycleCase.class), eq(OPERATOR_ID), eq(TENANT))).thenReturn(plan, plan);
        ResignationGateChecker.AutomaticActionResult actions =
                new ResignationGateChecker.AutomaticActionResult(LINKED_USER_ID, List.of());
        when(resignationGateChecker.applyAutomaticScopeActions(locked, engineer)).thenReturn(actions);

        service.completeCase(CASE_ID, OPERATOR_ID);

        InOrder order = inOrder(caseMapper, completionLockPlanner, lockExecutor, resignationGateChecker,
                revocationOutbox, engineerMapper, eventMapper);
        // discovery（lockを取らない）
        order.verify(caseMapper).selectByIdAndTenant(CASE_ID, TENANT);
        order.verify(resignationGateChecker).evaluate(discovered, engineer);
        order.verify(completionLockPlanner).plan(discovered, OPERATOR_ID, TENANT);
        // 3 S{操作者}・X{連携ユーザー} → 4 → 5 → 7 → 8 → 9 → 10 → 14 を1回で取る
        order.verify(lockExecutor).acquire(plan);
        // lock後の再discoveryと前提の再判定
        order.verify(caseMapper).selectByIdAndTenant(CASE_ID, TENANT);
        order.verify(completionLockPlanner).plan(locked, OPERATOR_ID, TENANT);
        order.verify(resignationGateChecker).evaluate(locked, engineer);
        // 取得済みの行への書込み（3・8・9・10 → 14 → 7 → 4）と葉
        order.verify(resignationGateChecker).applyAutomaticScopeActions(locked, engineer);
        order.verify(revocationOutbox).enqueueForResignation(locked, OPERATOR_ID);
        order.verify(engineerMapper).updateById(engineer);
        order.verify(caseMapper).updateById(locked);
        order.verify(eventMapper).insert(any(LifecycleEvent.class));
        order.verify(resignationGateChecker).revokeSessionsAfterScopeActions(actions);
        verify(lockExecutor, times(1)).acquire(any());
        verify(caseMapper, never()).selectByIdForUpdateAndTenant(any(), any());
        verify(engineerMapper, never()).selectByIdForUpdateForTenant(any(), any());
        assertEquals("Bench", engineer.getStatus());
        assertEquals("COMPLETED", locked.getStatus());

        // planは連携ユーザー（rank 3）を要員（rank 7）より先に並べる（旧実装の 4 → 7 → 3 を消す）。
        List<Rank> ranks = plan.locks().stream().map(LockKey::rank).toList();
        assertEquals(List.of(Rank.SYS_USER, Rank.SYS_USER, Rank.LIFECYCLE_CASE, Rank.LIFECYCLE_TASK, Rank.ENGINEER,
                Rank.USER_ORGANIZATION, Rank.ENGINEER_ACCOUNT_LINK, Rank.ENGINEER_SALES,
                Rank.EXTERNAL_ACCOUNT_REFERENCE), ranks);
    }

    @Test
    void lock対象がdiscovery後に消えていれば409で止め何も書かない() {
        LifecycleCase discovered = resignation("ACTIVE");
        stubResignation(discovered, resignation("ACTIVE"));
        LifecycleLockPlan plan = resignationPlan();
        when(completionLockPlanner.plan(any(LifecycleCase.class), eq(OPERATOR_ID), eq(TENANT))).thenReturn(plan);
        doThrow(new LifecycleLockPlan.DependencyChangedException()).when(lockExecutor).acquire(plan);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.completeCase(CASE_ID, OPERATOR_ID));

        assertEquals(409, ex.getCode());
        assertEquals("error.common.optimisticLock", ex.getMessageKey());
        assertNoWrites();
    }

    @Test
    void lockの前後で依存先が変われば途中でlockを足さず409で止め何も書かない() {
        LifecycleCase discovered = resignation("ACTIVE");
        LifecycleCase locked = resignation("ACTIVE");
        stubResignation(discovered, locked);
        LifecycleLockPlan before = resignationPlan();
        // lockまでの間に担当営業が1件追加された（rank 10 の集合が変わった）。
        LifecycleLockPlan after = LifecycleLockPlan.from(appended(before,
                LockKey.exclusive(Rank.ENGINEER_SALES, "", 81L)));
        when(completionLockPlanner.plan(any(LifecycleCase.class), eq(OPERATOR_ID), eq(TENANT))).thenReturn(before, after);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.completeCase(CASE_ID, OPERATOR_ID));

        assertEquals(409, ex.getCode());
        assertEquals("error.common.optimisticLock", ex.getMessageKey());
        verify(lockExecutor, times(1)).acquire(before);
        assertNoWrites();
    }

    @Test
    void lock前の判定で完了できない要求はplanを作らずlockも取らない() {
        LifecycleCase completed = resignation("COMPLETED");
        stubActor();
        when(caseMapper.selectByIdAndTenant(CASE_ID, TENANT)).thenReturn(completed);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.completeCase(CASE_ID, OPERATOR_ID));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.invalidStateTransition", ex.getMessageKey());
        verifyNoInteractions(completionLockPlanner, lockExecutor);
    }

    @Test
    void lock後に案件の状態が変わっていれば書込み前に拒否する() {
        LifecycleCase discovered = resignation("ACTIVE");
        LifecycleCase cancelled = resignation("CANCELLED");
        stubResignation(discovered, cancelled);
        LifecycleLockPlan plan = resignationPlan();
        when(completionLockPlanner.plan(any(LifecycleCase.class), eq(OPERATOR_ID), eq(TENANT))).thenReturn(plan, plan);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.completeCase(CASE_ID, OPERATOR_ID));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.invalidStateTransition", ex.getMessageKey());
        verify(lockExecutor).acquire(plan);
        assertNoWrites();
    }

    // ===== helper =====

    private void assertNoWrites() {
        verify(resignationGateChecker, never()).applyAutomaticScopeActions(any(), any());
        verify(resignationGateChecker, never()).revokeSessionsAfterScopeActions(any());
        verifyNoInteractions(revocationOutbox);
        verify(engineerMapper, never()).updateById(any(Engineer.class));
        verify(caseMapper, never()).updateById(any(LifecycleCase.class));
        verify(eventMapper, never()).insert(any(LifecycleEvent.class));
    }

    private Engineer stubResignation(LifecycleCase discovered, LifecycleCase locked) {
        stubActor();
        when(caseMapper.selectByIdAndTenant(CASE_ID, TENANT)).thenReturn(discovered, locked);
        Engineer engineer = new Engineer();
        engineer.setId(ENGINEER_ID);
        engineer.setTenantId(TENANT);
        engineer.setStatus("稼動中");
        when(engineerMapper.selectOne(any())).thenReturn(engineer);
        when(engineerMapper.updateById(any(Engineer.class))).thenReturn(1);
        when(taskMapper.selectByCaseIdAndTenant(CASE_ID, TENANT)).thenReturn(List.of());
        when(resignationGateChecker.evaluate(any(), any()))
                .thenReturn(ResignationGateResultDto.builder().passed(true).build());
        when(caseMapper.updateById(any(LifecycleCase.class))).thenReturn(1);
        return engineer;
    }

    private void stubActor() {
        SysUser operator = new SysUser();
        operator.setId(OPERATOR_ID);
        operator.setTenantId(TENANT);
        operator.setRole("HR");
        operator.setStatus(1);
        when(sysUserMapper.selectById(OPERATOR_ID)).thenReturn(operator);
        when(scopeService.requireTenant(any())).thenReturn(TENANT);
    }

    /** 計画 §3.4 の completeCase 列（planの値。並びは LifecycleLockPlan が rank 順に整える）。 */
    private static LifecycleLockPlan resignationPlan() {
        return LifecycleLockPlan.from(List.of(
                LockKey.exclusive(Rank.EXTERNAL_ACCOUNT_REFERENCE, TENANT, 91L),
                LockKey.exclusive(Rank.ENGINEER_SALES, "", 71L),
                LockKey.exclusive(Rank.ENGINEER_ACCOUNT_LINK, TENANT, 61L),
                LockKey.exclusive(Rank.USER_ORGANIZATION, TENANT, 51L),
                LockKey.exclusive(Rank.ENGINEER, TENANT, ENGINEER_ID),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, TENANT, 11L),
                LockKey.exclusive(Rank.LIFECYCLE_CASE, TENANT, CASE_ID),
                LockKey.shared(Rank.SYS_USER, TENANT, OPERATOR_ID),
                LockKey.exclusive(Rank.SYS_USER, TENANT, LINKED_USER_ID)));
    }

    private static List<LockKey> appended(LifecycleLockPlan plan, LockKey extra) {
        List<LockKey> keys = new java.util.ArrayList<>(plan.locks());
        keys.add(extra);
        return keys;
    }

    private static LifecycleCase resignation(String status) {
        LifecycleCase lcCase = LifecycleCase.builder()
                .tenantId(TENANT)
                .caseNo("LC-UNIT-RESIGN")
                .lifecycleType("RESIGNATION")
                .engineerId(ENGINEER_ID)
                .anchorDate(LocalDate.of(2026, 11, 30))
                .status(status)
                .title("unit")
                .applicantUserId(OPERATOR_ID)
                .build();
        lcCase.setId(CASE_ID);
        return lcCase;
    }
}
