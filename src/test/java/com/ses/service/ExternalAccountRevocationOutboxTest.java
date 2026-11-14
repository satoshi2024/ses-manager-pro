package com.ses.service;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.LifecycleCase;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.ses.service.ExternalAccountRevocationStates.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** U10 / §5.13: transaction内の登録とcommit後送信、法人所有権、generation、履歴を検証する。 */
@ExtendWith(MockitoExtension.class)
class ExternalAccountRevocationOutboxTest {
    @Mock ExternalAccountReferenceMapper references;
    @Mock LifecycleCaseMapper cases;
    @Mock AssetEventService events;
    @Mock SysUserMapper users;
    @Mock NotificationService notifications;
    @Mock ObjectProvider<ExternalAccountRevocationDispatcher> provider;
    @Mock ExternalAccountRevocationDispatcher dispatcher;
    ExternalAccountRevocationOutbox outbox;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-14T01:00:00Z"), ZoneId.of("Asia/Tokyo"));

    @BeforeEach void setUp() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        outbox = new ExternalAccountRevocationOutbox(references, cases, events, users, notifications, provider, clock);
    }
    @AfterEach void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        AccountingTenantContextHolder.clear();
    }

    @Test void 全法人の行を各法人で登録しcommit後だけdispatcherを呼ぶ() {
        ExternalAccountReference a = row(11L, 1L), b = row(12L, 2L);
        when(references.selectUnrevokedForEngineer("tenant-a", 7L)).thenReturn(List.of(a, b));
        when(references.selectByIdForUpdate(11L, "tenant-a", 1L)).thenReturn(a);
        when(references.selectByIdForUpdate(12L, "tenant-a", 2L)).thenReturn(b);
        when(references.enqueueRevoke(any(), any(), any(), any(), isNull(), any(), any(), any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        outbox.enqueueForResignation(resignation(), 9L);
        verifyNoInteractions(dispatcher, provider);
        verify(references).enqueueRevoke(eq(11L), eq("tenant-a"), eq(1L), eq(0), isNull(),
                eq("lifecycle-resign:100:11:g1"), eq(9L), any());
        verify(references).enqueueRevoke(eq(12L), eq("tenant-a"), eq(2L), eq(0), isNull(),
                eq("lifecycle-resign:100:12:g1"), eq(9L), any());
        when(provider.getObject()).thenReturn(dispatcher);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(dispatcher).dispatchPending();
        verify(events, times(2)).recordExternalAccountConfirmation(any(), eq("EXTERNAL_ACCOUNT_REVOKE_OUTBOX"),
                any(), eq(REQUESTED), any(), any(), any());
    }

    @Test void rollback時はdispatcherを呼ばない() {
        when(references.selectUnrevokedForEngineer("tenant-a", 7L)).thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();
        outbox.enqueueForResignation(resignation(), 9L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(dispatcher, provider);
    }

    @Test void 法人不明のアカウントでは登録を拒否する() {
        when(references.selectUnrevokedForEngineer("tenant-a", 7L)).thenReturn(List.of(row(11L, null)));
        assertThatThrownBy(() -> outbox.enqueueForResignation(resignation(), 9L)).isInstanceOf(BusinessException.class);
        verify(references, never()).enqueueRevoke(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void 別経路で登録済みの要求は上書きしない() {
        ExternalAccountReference row = row(11L, 1L);
        row.setIdempotencyKey("existing-request");
        when(references.selectUnrevokedForEngineer("tenant-a", 7L)).thenReturn(List.of(row));
        when(references.selectByIdForUpdate(11L, "tenant-a", 1L)).thenReturn(row);
        outbox.enqueueForResignation(resignation(), 9L);
        verify(references, never()).enqueueRevoke(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void 再入社があれば事後登録を自動失効しない() {
        ExternalAccountReference row = row(11L, 1L);
        when(cases.selectLatestCompletedResignationWithoutReentry("tenant-a", 7L)).thenReturn(null);
        outbox.enqueueLateRegistration(row, 9L);
        verifyNoInteractions(references, events, notifications);
    }

    @Test void 退社後の事後登録は同じ案件で登録する() {
        ExternalAccountReference row = row(11L, 1L);
        when(cases.selectLatestCompletedResignationWithoutReentry("tenant-a", 7L)).thenReturn(resignation());
        when(references.enqueueRevoke(any(), any(), any(), any(), isNull(), any(), any(), any())).thenReturn(1);
        outbox.enqueueLateRegistration(row, 9L);
        verify(references).enqueueRevoke(eq(11L), eq("tenant-a"), eq(1L), eq(0), isNull(),
                eq("lifecycle-resign:100:11:g1"), eq(9L), any());
    }

    @Test void 結果不明からの再要求は旧keyを残してgenerationを増やす() {
        ExternalAccountReference row = row(11L, 1L);
        row.setStatus("UNKNOWN"); row.setIdempotencyKey("lifecycle-resign:100:11:g1"); row.setExternalSyncStatus(UNKNOWN);
        when(references.selectByIdForUpdate(11L, "tenant-a", 1L)).thenReturn(row);
        when(references.enqueueRevoke(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        outbox.reRequestUnknown(11L, "tenant-a", 1L, 9L);
        verify(references).enqueueRevoke(eq(11L), eq("tenant-a"), eq(1L), eq(0),
                eq("lifecycle-resign:100:11:g1"), eq("lifecycle-resign:100:11:g2"), eq(9L), any());
        verify(events).recordExternalAccountConfirmation(eq(11L), any(), eq(UNKNOWN), eq(REQUESTED), any(), any(), any());
    }

    @Test void 非冪等providerのlease切れは再送可能状態へ戻さない() {
        ExternalAccountReference row = row(11L, 1L);
        row.setIdempotencyKey("lifecycle-resign:100:11:g1"); row.setExternalSyncStatus(SENDING);
        row.setNextRetryAt(LocalDateTime.now(clock).minusSeconds(1));
        when(references.transitionOutbox(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(1);
        outbox.recoverExpiredLease(row, false);
        verify(references).transitionOutbox(eq(11L), eq("tenant-a"), eq(1L), eq(0), any(), eq(SENDING),
                eq(UNKNOWN), eq("UNKNOWN"), eq(0), any(), eq("PROVIDER_REVOKE_OUTCOME_UNKNOWN"));
    }

    @Test void 登録CAS失敗なら履歴やcommit後送信を追加しない() {
        ExternalAccountReference row = row(11L, 1L);
        when(references.selectUnrevokedForEngineer("tenant-a", 7L)).thenReturn(List.of(row));
        when(references.selectByIdForUpdate(11L, "tenant-a", 1L)).thenReturn(row);
        assertThatThrownBy(() -> outbox.enqueueForResignation(resignation(), 9L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(events, provider);
    }

    private ExternalAccountReference row(Long id, Long legal) {
        ExternalAccountReference row = ExternalAccountReference.builder().tenantId("tenant-a").legalEntityId(legal)
                .assigneeType("ENGINEER").assigneeId(7L).status("ACTIVE").version(0).externalSyncStatus("NONE").build();
        row.setId(id); return row;
    }
    private LifecycleCase resignation() {
        LifecycleCase result = LifecycleCase.builder().tenantId("tenant-a").engineerId(7L).lifecycleType("RESIGNATION").build();
        result.setId(100L); return result;
    }
}
