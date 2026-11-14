package com.ses.service.lifecycle.evidence;

import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.AuthorizationEpochMapper.EpochRow;
import com.ses.mapper.LifecycleLockMapper;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.LockKey;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Mode;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Rank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF01細粒度epoch計画 §3.3・§3.5: lock planの実行順、case X → task X、取得済みkey以前のkeyの拒否、対象行欠落の扱いを
 * DB無しで固定する（実MySQLでのlock modeと待ちは AuthorizationEpochSchemaMySqlTest）。
 */
class LifecycleLockExecutorTest {

    private LifecycleLockMapper lockMapper;
    private AuthorizationEpochMapper epochMapper;
    private LifecycleLockExecutor executor;

    @BeforeEach
    void setUp() {
        lockMapper = mock(LifecycleLockMapper.class);
        epochMapper = mock(AuthorizationEpochMapper.class);
        executor = new LifecycleLockExecutor(lockMapper, epochMapper);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations,
                    TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    void planの順にrankごとのlockを取りtaskは同じtenantのlock済み案件に限って取る() {
        stubAllPresent();
        executor.acquire(LifecycleLockPlan.from(List.of(
                LockKey.shared(Rank.DOCUMENT_VERSION, "tenant-a", 61),
                LockKey.shared(Rank.DOCUMENT, "tenant-a", 60),
                LockKey.shared(Rank.ENGINEER, "tenant-a", 7),
                LockKey.shared(Rank.ORGANIZATION, "", 12),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, "tenant-a", 31),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, "tenant-a", 30),
                LockKey.exclusive(Rank.LIFECYCLE_CASE, "tenant-a", 20),
                LockKey.shared(Rank.SYS_USER, "tenant-a", 4),
                LockKey.shared(Rank.SYS_USER, "tenant-a", 3),
                LockKey.global(Mode.SHARED))));

        InOrder order = inOrder(epochMapper, lockMapper);
        order.verify(epochMapper).selectGlobalForShare();
        order.verify(lockMapper).lockSysUser(3, "tenant-a", false);
        order.verify(lockMapper).lockSysUser(4, "tenant-a", false);
        order.verify(lockMapper).lockLifecycleCase(20, "tenant-a");
        order.verify(lockMapper).lockLifecycleTask(30, List.of(20L));
        order.verify(lockMapper).lockLifecycleTask(31, List.of(20L));
        order.verify(lockMapper).lockOrganization(12, false);
        order.verify(lockMapper).lockEngineer(7, "tenant-a", false);
        order.verify(lockMapper).lockDocument(60, "tenant-a", false);
        order.verify(lockMapper).lockDocumentVersion(61, "tenant-a", false);
        verify(epochMapper, never()).selectGlobalForUpdate();
    }

    @Test
    void 同じ行のSとXはXで1回だけ取る() {
        stubAllPresent();
        executor.acquire(LifecycleLockPlan.from(List.of(
                LockKey.shared(Rank.ENGINEER, "tenant-a", 7),
                LockKey.exclusive(Rank.ENGINEER, "tenant-a", 7),
                LockKey.global(Mode.EXCLUSIVE),
                LockKey.global(Mode.SHARED))));

        verify(epochMapper).selectGlobalForUpdate();
        verify(epochMapper, never()).selectGlobalForShare();
        verify(lockMapper).lockEngineer(7, "tenant-a", true);
        verify(lockMapper, never()).lockEngineer(7, "tenant-a", false);
    }

    @Test
    void 対象行が無ければ依存変更として止め後続のrankを取らない() {
        stubAllPresent();
        when(lockMapper.lockEngineer(7, "tenant-a", false)).thenReturn(null);

        assertThrows(LifecycleLockPlan.DependencyChangedException.class, () -> executor.acquire(LifecycleLockPlan.from(
                List.of(LockKey.shared(Rank.SYS_USER, "tenant-a", 3), LockKey.shared(Rank.ENGINEER, "tenant-a", 7),
                        LockKey.shared(Rank.DOCUMENT, "tenant-a", 60)))));
        verify(lockMapper).lockSysUser(3, "tenant-a", false);
        verify(lockMapper, never()).lockDocument(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void 同じtenantの案件をXで取らないplanのtaskは拒否しlockを始めない() {
        assertThrows(IllegalArgumentException.class, () -> executor.acquire(LifecycleLockPlan.from(List.of(
                LockKey.exclusive(Rank.LIFECYCLE_TASK, "tenant-a", 30)))));
        assertThrows(IllegalArgumentException.class, () -> executor.acquire(LifecycleLockPlan.from(List.of(
                LockKey.exclusive(Rank.LIFECYCLE_CASE, "tenant-b", 20),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, "tenant-a", 30)))));
        verifyNoInteractions(lockMapper, epochMapper);
    }

    @Test
    void 同じtransactionで取得済みのkey以前のkeyは後から取らせない() {
        stubAllPresent();
        executor.acquire(LifecycleLockPlan.from(List.of(LockKey.shared(Rank.ENGINEER, "tenant-a", 7))));

        assertThrows(IllegalStateException.class, () -> executor.acquire(LifecycleLockPlan.from(List.of(
                LockKey.shared(Rank.SYS_USER, "tenant-a", 3)))));
        assertThrows(IllegalStateException.class, () -> executor.acquire(LifecycleLockPlan.from(List.of(
                LockKey.shared(Rank.ENGINEER, "tenant-a", 7)))));
        verify(lockMapper, never()).lockSysUser(anyLong(), anyString(), anyBoolean());

        executor.acquire(LifecycleLockPlan.from(List.of(LockKey.shared(Rank.DOCUMENT, "tenant-a", 60))));
        verify(lockMapper).lockDocument(60, "tenant-a", false);
    }

    @Test
    void transaction外では拒否する() {
        tearDown();
        assertThrows(IllegalStateException.class, () -> executor.acquire(LifecycleLockPlan.from(List.of(
                LockKey.global(Mode.SHARED)))));
        verifyNoInteractions(lockMapper, epochMapper);
    }

    private void stubAllPresent() {
        when(epochMapper.selectGlobalForShare()).thenReturn(new EpochRow(0L, 0));
        when(epochMapper.selectGlobalForUpdate()).thenReturn(new EpochRow(0L, 0));
        when(lockMapper.lockSysUser(anyLong(), anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(0));
        when(lockMapper.lockLifecycleCase(anyLong(), anyString())).thenAnswer(call -> call.getArgument(0));
        when(lockMapper.lockLifecycleTask(anyLong(), eq(List.of(20L)))).thenAnswer(call -> call.getArgument(0));
        when(lockMapper.lockOrganization(anyLong(), anyBoolean())).thenAnswer(call -> call.getArgument(0));
        when(lockMapper.lockEngineer(anyLong(), anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(0));
        when(lockMapper.lockDocument(anyLong(), anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(0));
        when(lockMapper.lockDocumentVersion(anyLong(), anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(0));
    }
}
