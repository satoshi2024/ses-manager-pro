package com.ses.service;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.mapper.AttendanceScopeMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.impl.ExternalAccountServiceImpl;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 外部アカウント台帳のtenant・法人境界をサービス入口とpoll workerで検証する。 */
@ExtendWith(MockitoExtension.class)
class ExternalAccountTenantBoundaryTest {

    @Mock ExternalAccountSystemMapper systemMapper;
    @Mock ExternalAccountReferenceMapper referenceMapper;
    @Mock ExternalAccountProviderClient providerClient;
    @Mock ExternalAccountRevokeConfirmationService confirmationService;
    @Mock LegalEntityContextService legalEntityContextService;
    @Mock EngineerMapper engineerMapper;
    @Mock SysUserMapper sysUserMapper;
    @Mock AttendanceScopeMapper attendanceScopeMapper;
    @Mock ExternalAccountRevocationOutbox revocationOutbox;

    @InjectMocks ExternalAccountServiceImpl service;

    @BeforeEach
    void bindScope() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        org.mockito.Mockito.lenient().when(legalEntityContextService.requireTenantId()).thenReturn("tenant-a");
        org.mockito.Mockito.lenient().when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(11L);
    }

    @AfterEach
    void clearScope() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 登録は要員ロック待機前の古い退社snapshotを使わない分離レベルを宣言する() throws Exception {
        for (String name : new String[]{"registerAccountReference", "createAccountReference"}) {
            var method = ExternalAccountServiceImpl.class.getMethod(name,
                    Long.class, String.class, String.class, Long.class, String.class, Long.class);
            assertThat(method.getDeclaringClass()).isEqualTo(ExternalAccountServiceImpl.class);
            assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).isolation())
                    .isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
        }
    }

    @Test
    void 外側のRRはscopeや要員を読む前に拒否する() {
        boolean previous = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        Integer previousIsolation = org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        try {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            assertThatThrownBy(() -> service.registerAccountReference(7L, "synthetic", "ENGINEER", 21L, "MEMBER", 1L))
                    .isInstanceOf(BusinessException.class).hasMessage("error.asset.revocationIsolationRequired");
            org.mockito.Mockito.verifyNoInteractions(legalEntityContextService, engineerMapper, referenceMapper, revocationOutbox);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(previous);
            org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(previousIsolation);
        }
    }

    @Test
    void 管理者相当でも別tenant法人の更新は取得段階で拒否する() {
        when(referenceMapper.selectByIdAndScope(42L, "tenant-a", 11L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateAccountReference(42L, "new@example.jp", "MEMBER", 1L))
                .isInstanceOf(BusinessException.class);

        verify(referenceMapper).selectByIdAndScope(42L, "tenant-a", 11L);
        verify(referenceMapper, never()).update(any(), any());
    }

    @Test
    void 登録は要員ownershipを検証してtenant法人を刻印する() {
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("TEST").systemName("Test").systemType("IDP").build();
        system.setId(7L);
        Engineer engineer = Engineer.builder().tenantId("tenant-a").legalEntityId(11L)
                .fullName("境界テスト要員").build();
        engineer.setId(21L);
        when(systemMapper.selectById(7L)).thenReturn(system);
        when(engineerMapper.selectByIdForUpdateForTenant(21L, "tenant-a")).thenReturn(engineer);
        when(referenceMapper.insert(any(ExternalAccountReference.class))).thenAnswer(invocation -> {
            ExternalAccountReference inserted = invocation.getArgument(0);
            inserted.setId(100L);
            return 1;
        });

        ExternalAccountReference registered = service.registerAccountReference(
                7L, "user@example.jp", "engineer", 21L, "MEMBER", 1L);

        assertThat(registered.getTenantId()).isEqualTo("tenant-a");
        assertThat(registered.getLegalEntityId()).isEqualTo(11L);
        assertThat(registered.getAssigneeType()).isEqualTo("ENGINEER");
    }

    @Test
    void 登録は別法人要員を拒否する() {
        Engineer otherLegalEntity = Engineer.builder().tenantId("tenant-a").legalEntityId(12L)
                .fullName("別法人要員").build();
        otherLegalEntity.setId(22L);
        when(engineerMapper.selectByIdForUpdateForTenant(22L, "tenant-a")).thenReturn(otherLegalEntity);

        assertThatThrownBy(() -> service.registerAccountReference(
                7L, "other@example.jp", "ENGINEER", 22L, "MEMBER", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ASSIGNEE_SCOPE_MISMATCH");

        verify(referenceMapper, never()).insert(any(ExternalAccountReference.class));
    }

    @Test
    void 失効要求登録後は識別子や状態を直接書き換えない() {
        ExternalAccountReference row = ExternalAccountReference.builder().tenantId("tenant-a").legalEntityId(11L)
                .status("PENDING_CONFIRMATION").idempotencyKey("lifecycle-resign:1:42:g1")
                .externalSyncStatus("REVOKE_REQUESTED").build(); row.setId(42L);
        when(referenceMapper.selectByIdAndScope(42L, "tenant-a", 11L)).thenReturn(row);
        assertThatThrownBy(() -> service.updateAccountReference(42L, "changed", "ADMIN", 1L))
                .isInstanceOf(BusinessException.class).hasMessage("error.asset.revocationTargetFrozen");
        assertThatThrownBy(() -> service.changeStatus(42L, "ACTIVE", 1L))
                .isInstanceOf(BusinessException.class).hasMessage("error.asset.revocationTargetFrozen");
        row.setStatus("REVOKED");
        assertThatThrownBy(() -> service.updateAccountReference(42L, "different-target", "MEMBER", 1L))
                .isInstanceOf(BusinessException.class).hasMessage("error.asset.revocationTargetFrozen");
        verify(referenceMapper, never()).update(any(), any());
    }

    @Test
    void pollはinventoryで束縛されたtenantと行の法人ownershipでclaimする() {
        ExternalAccountReference due = ExternalAccountReference.builder()
                .tenantId("tenant-a").legalEntityId(11L).status("PENDING_CONFIRMATION")
                .retryCount(0).version(0).build();
        due.setId(30L);
        ExternalAccountReference claimed = ExternalAccountReference.builder()
                .tenantId("tenant-a").legalEntityId(11L).status("PENDING_CONFIRMATION")
                .retryCount(0).version(1).idempotencyKey("idem-30").build();
        claimed.setId(30L);
        when(referenceMapper.selectPendingForTenant(any(), any(LocalDateTime.class)))
                .thenReturn(List.of(due));
        when(referenceMapper.claimRevokePoll(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(referenceMapper.selectByIdAndScope(30L, "tenant-a", 11L)).thenReturn(claimed);
        when(providerClient.checkRevokeConfirmation(claimed))
                .thenReturn(ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED);

        int processed = service.processPendingRevokePollJob();

        assertThat(processed).isEqualTo(1);
        verify(referenceMapper).claimRevokePoll(
                org.mockito.ArgumentMatchers.eq(30L), org.mockito.ArgumentMatchers.eq(0),
                any(LocalDateTime.class), any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq("tenant-a"), org.mockito.ArgumentMatchers.eq(11L));
        verify(confirmationService).confirm(any(), any());
    }
}
