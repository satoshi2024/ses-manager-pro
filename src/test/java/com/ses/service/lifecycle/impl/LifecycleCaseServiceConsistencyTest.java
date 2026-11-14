package com.ses.service.lifecycle.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleEvent;
import com.ses.entity.LifecycleTask;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleEventMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.lifecycle.LifecycleNotificationService;
import com.ses.service.lifecycle.LifecycleScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF01 batch 0: cancelCase のタスクCAS検査（L4）と completeCase 通知のafterCommit化。
 */
@ExtendWith(MockitoExtension.class)
class LifecycleCaseServiceConsistencyTest {

    private static final String TENANT = "tenant-a";
    private static final Long ACTOR_ID = 1L;
    private static final Long CASE_ID = 10L;
    private static final Long ENGINEER_ID = 20L;

    @Mock private LifecycleCaseMapper caseMapper;
    @Mock private LifecycleTaskMapper taskMapper;
    @Mock private LifecycleEventMapper eventMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private LifecycleScopeService scopeService;
    @Mock private LifecycleNotificationService notificationService;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private LifecycleCaseServiceImpl service;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cancelCaseはタスクCASが0件なら409で中断し中止イベントを残さない() {
        LifecycleCase lcCase = activeCase("JOIN");
        stubActorAndCase(lcCase);
        when(caseMapper.updateById(any(LifecycleCase.class))).thenReturn(1);
        LifecycleTask task = LifecycleTask.builder().caseId(CASE_ID).status("IN_PROGRESS").build();
        task.setId(100L);
        when(taskMapper.selectByCaseIdAndTenant(CASE_ID, TENANT)).thenReturn(List.of(task));
        when(taskMapper.selectByIdAndCaseIdForUpdate(100L, CASE_ID)).thenReturn(task);
        when(taskMapper.updateById(any(LifecycleTask.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelCase(CASE_ID, ACTOR_ID, "中止"));

        assertEquals(409, ex.getCode());
        assertEquals("error.common.optimisticLock", ex.getMessageKey());
        verify(eventMapper, never()).insert(any(LifecycleEvent.class));
    }

    @Test
    void cancelCaseは案件ロック後にタスクをid昇順でロックしてから更新する() {
        LifecycleCase lcCase = activeCase("JOIN");
        stubActorAndCase(lcCase);
        when(caseMapper.updateById(any(LifecycleCase.class))).thenReturn(1);
        LifecycleTask high = LifecycleTask.builder().caseId(CASE_ID).status("PENDING").build();
        high.setId(300L);
        LifecycleTask low = LifecycleTask.builder().caseId(CASE_ID).status("IN_PROGRESS").build();
        low.setId(200L);
        when(taskMapper.selectByCaseIdAndTenant(CASE_ID, TENANT)).thenReturn(List.of(high, low));
        when(taskMapper.selectByIdAndCaseIdForUpdate(200L, CASE_ID)).thenReturn(low);
        when(taskMapper.selectByIdAndCaseIdForUpdate(300L, CASE_ID)).thenReturn(high);
        when(taskMapper.updateById(any(LifecycleTask.class))).thenReturn(1);

        service.cancelCase(CASE_ID, ACTOR_ID, "中止");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(caseMapper, taskMapper);
        order.verify(caseMapper).selectByIdForUpdateAndTenant(CASE_ID, TENANT);
        order.verify(taskMapper).selectByIdAndCaseIdForUpdate(200L, CASE_ID);
        order.verify(taskMapper).selectByIdAndCaseIdForUpdate(300L, CASE_ID);
        assertEquals("CANCELLED", low.getStatus());
        assertEquals("CANCELLED", high.getStatus());
    }

    @Test
    void completeCaseの完了通知はcommit後に独立transactionで発行される() {
        stubCompletableCase();
        TransactionSynchronizationManager.initSynchronization();

        service.completeCase(CASE_ID, ACTOR_ID);

        verifyNoInteractions(notificationService);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(notificationService).notifyCaseCompleted(any(LifecycleCase.class));
        verify(transactionManager).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void completeCaseがrollbackされた場合は完了通知を発行しない() {
        stubCompletableCase();
        TransactionSynchronizationManager.initSynchronization();

        service.completeCase(CASE_ID, ACTOR_ID);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(notificationService);
    }

    @Test
    void 完了通知の失敗はcommit済みの完了処理へ伝播しない() {
        stubCompletableCase();
        doThrow(new IllegalStateException("notification down")).when(notificationService).notifyCaseCompleted(any());
        TransactionSynchronizationManager.initSynchronization();

        service.completeCase(CASE_ID, ACTOR_ID);

        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit));
    }

    private void stubCompletableCase() {
        LifecycleCase lcCase = activeCase("JOIN");
        stubActorAndCase(lcCase);
        when(taskMapper.selectByCaseIdAndTenant(CASE_ID, TENANT)).thenReturn(List.of());
        when(caseMapper.updateById(any(LifecycleCase.class))).thenReturn(1);
    }

    private void stubActorAndCase(LifecycleCase lcCase) {
        SysUser actor = new SysUser();
        actor.setId(ACTOR_ID);
        actor.setTenantId(TENANT);
        actor.setRole("管理者");
        actor.setStatus(1);
        when(sysUserMapper.selectById(ACTOR_ID)).thenReturn(actor);
        when(scopeService.requireTenant(any())).thenReturn(TENANT);
        when(caseMapper.selectByIdForUpdateAndTenant(CASE_ID, TENANT)).thenReturn(lcCase);
        Engineer engineer = new Engineer();
        engineer.setId(ENGINEER_ID);
        engineer.setTenantId(TENANT);
        when(engineerMapper.selectOne(any())).thenReturn(engineer);
    }

    private LifecycleCase activeCase(String lifecycleType) {
        LifecycleCase lcCase = LifecycleCase.builder()
                .tenantId(TENANT)
                .caseNo("LC-UNIT-1")
                .lifecycleType(lifecycleType)
                .engineerId(ENGINEER_ID)
                .anchorDate(LocalDate.of(2026, 11, 30))
                .status("ACTIVE")
                .title("unit")
                .applicantUserId(ACTOR_ID)
                .build();
        lcCase.setId(CASE_ID);
        return lcCase;
    }
}
