package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.AdministratorConditionCheck;
import com.ses.config.AdministratorConditionCheck.Condition;
import com.ses.config.AdministratorStartupConditions;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RB-ADMIN-GUARD: 管理者の継続性guardの判定（本番だけ・前は満たし後は満たさない変更だけを拒否・起動時検査が判定しない条件は
 * 判定しない・lockの順と範囲・入れ子・rollback後の監査）を単体で固定する。条件の値そのものは起動時検査が作る
 * （{@code ProductionSecurityConfigurationValidatorTest}・{@code PrivacyProductionConfigurationValidatorTest}）。
 */
class AdministratorContinuityGuardTest {

    private final Environment environment = mock(Environment.class);
    private final SysUserMapper sysUserMapper = mock(SysUserMapper.class);
    private final AdministratorStartupConditions security = mock(AdministratorStartupConditions.class);
    private final AdministratorStartupConditions privacy = mock(AdministratorStartupConditions.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private AdministratorContinuityGuard guard;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ops-operator", "n/a", List.of()));
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        when(sysUserMapper.selectAdministratorContinuityLockTargets(eq("管理者"), any()))
                .thenReturn(List.of(user(10L, "default")));
        when(privacy.checkAdministratorConditions()).thenReturn(List.of(privacy(2)));
        guard = guard(List.of(security, privacy));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
        }
        new ArrayList<>(TransactionSynchronizationManager.getResourceMap().keySet())
                .forEach(TransactionSynchronizationManager::unbindResourceIfPossible);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        SecurityContextHolder.clearContext();
    }

    @Test
    void prod以外ではlockも照会もせず判定しない() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);

        AdministratorContinuityGuard.Snapshot locked = guard.lockAndCapture("USER_STATUS", List.of(10L));
        AdministratorContinuityGuard.Snapshot captured = guard.captureLocked("ENGINEER_DELETE", 10L);
        guard.verify(locked);
        guard.verify(captured);

        assertSame(AdministratorContinuityGuard.Snapshot.inactive(), locked);
        assertSame(AdministratorContinuityGuard.Snapshot.inactive(), captured);
        verifyNoInteractions(sysUserMapper, security, privacy, auditLogService, transactionManager);
        // prodの判定は起動時検査と同じ prod profile（acceptsProfiles）で行う。
        ArgumentCaptor<Profiles> profiles = ArgumentCaptor.forClass(Profiles.class);
        verify(environment, times(2)).acceptsProfiles(profiles.capture());
        assertTrue(profiles.getValue().matches("prod"::equals));
        assertFalse(profiles.getValue().matches("test"::equals));
    }

    @Test
    void 前は満たし後は満たさない条件があれば400で拒否し条件と変更後の人数と必要な人数を返す() {
        when(security.checkAdministratorConditions())
                .thenReturn(List.of(oidc(3), breakGlass(2)), List.of(oidc(2), breakGlass(2)));

        AdministratorContinuityGuard.Snapshot snapshot = guard.lockAndCapture("USER_STATUS", List.of(10L));
        BusinessException rejected = assertThrows(BusinessException.class, () -> guard.verify(snapshot));

        assertEquals(400, rejected.getCode());
        assertEquals("error.user.adminContinuity.oidcAdministrators", rejected.getMessageKey());
        assertArrayEquals(new Object[] {2, 3}, rejected.getArgs());
    }

    @Test
    void break_glassとprivacy_adminも前は満たし後は満たさなければそれぞれの文言で拒否する() {
        when(security.checkAdministratorConditions())
                .thenReturn(List.of(oidc(5), breakGlass(2)), List.of(oidc(5), breakGlass(1)),
                        List.of(oidc(5), breakGlass(2)), List.of(oidc(5), breakGlass(2)));
        when(privacy.checkAdministratorConditions())
                .thenReturn(List.of(privacy(2)), List.of(privacy(2)), List.of(privacy(2)), List.of(privacy(1)));

        AdministratorContinuityGuard.Snapshot first = guard.lockAndCapture("USER_UPDATE", List.of(10L));
        BusinessException breakGlass = assertThrows(BusinessException.class, () -> guard.verify(first));
        // 同じtransactionで続く別の操作は、前の操作の後の状態から判定し直す（verifyで印を外す）。
        AdministratorContinuityGuard.Snapshot second = guard.lockAndCapture("PERMISSION_GROUP_REPLACE", List.of(10L));
        BusinessException privacyAdmins = assertThrows(BusinessException.class, () -> guard.verify(second));

        assertEquals("error.user.adminContinuity.breakGlassAdministrators", breakGlass.getMessageKey());
        assertArrayEquals(new Object[] {1, 2}, breakGlass.getArgs());
        assertEquals("error.user.adminContinuity.privacyAdministrators", privacyAdmins.getMessageKey());
        assertArrayEquals(new Object[] {1, 2}, privacyAdmins.getArgs());
    }

    @Test
    void 前から満たさない条件と起動時検査が判定しない条件では拒否しない() {
        // OIDC管理者は前から不足（bootstrapで揃えている途中）、break-glassは起動時検査が判定しない（bootstrap中）。
        when(security.checkAdministratorConditions())
                .thenReturn(List.of(oidc(2), notEvaluated(Condition.BREAK_GLASS_ADMINISTRATORS, 2)),
                        List.of(oidc(1), notEvaluated(Condition.BREAK_GLASS_ADMINISTRATORS, 2)));
        when(privacy.checkAdministratorConditions()).thenReturn(List.of(privacy(1)), List.of(privacy(0)));

        AdministratorContinuityGuard.Snapshot snapshot = guard.lockAndCapture("USER_STATUS", List.of(10L));
        guard.verify(snapshot);

        assertTrue(snapshot.owner());
        verifyNoInteractions(auditLogService);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size(), "拒否の監査を登録しない");
    }

    @Test
    void 全管理者と対象をtenantとidの昇順で1行ずつlockしてから変更前を判定しlock後に増えた管理者があれば409() {
        when(security.checkAdministratorConditions()).thenReturn(List.of(oidc(3), breakGlass(2)));
        List<SysUser> discovered = List.of(user(30L, "tenant-b"), user(20L, "default"), user(10L, "default"));
        when(sysUserMapper.selectAdministratorContinuityLockTargets("管理者", List.of(10L)))
                .thenReturn(discovered, discovered);

        guard.lockAndCapture("USER_STATUS", List.of(10L));

        InOrder order = inOrder(sysUserMapper, security);
        order.verify(sysUserMapper).selectAdministratorContinuityLockTargets("管理者", List.of(10L));
        order.verify(sysUserMapper).selectByIdForUpdateIncludingDeleted(10L);
        order.verify(sysUserMapper).selectByIdForUpdateIncludingDeleted(20L);
        order.verify(sysUserMapper).selectByIdForUpdateIncludingDeleted(30L);
        order.verify(sysUserMapper).selectAdministratorContinuityLockTargets("管理者", List.of(10L));
        order.verify(security).checkAdministratorConditions();

        // lockの間に管理者（id=5）が増えた: lockを途中で足さず409にし、判定しない。
        AdministratorContinuityGuard other = guard(List.of(security, privacy));
        TransactionSynchronizationManager.unbindResourceIfPossible(resourceKeyOf(guard));
        when(sysUserMapper.selectAdministratorContinuityLockTargets("管理者", List.of(11L)))
                .thenReturn(List.of(user(11L, "default")), List.of(user(5L, "default"), user(11L, "default")));
        BusinessException conflict = assertThrows(BusinessException.class,
                () -> other.lockAndCapture("USER_STATUS", List.of(11L)));
        assertEquals(409, conflict.getCode());
        assertEquals("error.common.optimisticLock", conflict.getMessageKey());
        verify(sysUserMapper, never()).selectByIdForUpdateIncludingDeleted(5L);
        verify(security, times(1)).checkAdministratorConditions();
    }

    @Test
    void 同じtransactionの入れ子は外側の判定に任せlockも判定もしない() {
        when(security.checkAdministratorConditions())
                .thenReturn(List.of(oidc(4), breakGlass(2)), List.of(oidc(3), breakGlass(2)));

        AdministratorContinuityGuard.Snapshot outer = guard.lockAndCapture("USER_UPDATE", List.of(10L));
        AdministratorContinuityGuard.Snapshot nested = guard.lockAndCapture("PERMISSION_GROUP_REPLACE", List.of(10L));
        AdministratorContinuityGuard.Snapshot nestedLocked = guard.captureLocked("ENGINEER_DELETE", 10L);
        guard.verify(nested);
        guard.verify(nestedLocked);

        assertTrue(outer.owner());
        assertFalse(nested.owner());
        assertFalse(nestedLocked.owner());
        verify(sysUserMapper, times(2)).selectAdministratorContinuityLockTargets(eq("管理者"), any());
        verify(sysUserMapper, times(1)).selectByIdForUpdateIncludingDeleted(anyLong());
        verify(security, times(1)).checkAdministratorConditions();

        guard.verify(outer);
        verify(security, times(2)).checkAdministratorConditions();
    }

    @Test
    void 対象の行を呼出側がlock済みの経路は追加のlockを取らずに判定する() {
        when(security.checkAdministratorConditions())
                .thenReturn(List.of(oidc(3), breakGlass(2)), List.of(oidc(2), breakGlass(2)));

        AdministratorContinuityGuard.Snapshot snapshot = guard.captureLocked("RESIGNATION_COMPLETE", 30L);
        BusinessException rejected = assertThrows(BusinessException.class, () -> guard.verify(snapshot));

        assertEquals("error.user.adminContinuity.oidcAdministrators", rejected.getMessageKey());
        assertEquals(List.of(30L), snapshot.targetUserIds());
        verify(sysUserMapper, never()).selectAdministratorContinuityLockTargets(any(), any());
        verify(sysUserMapper, never()).selectByIdForUpdateIncludingDeleted(anyLong());
    }

    @Test
    void 拒否はrollback後に独立transactionで監査を残しcommitされた場合は残さない() {
        when(security.checkAdministratorConditions())
                .thenReturn(List.of(oidc(3), breakGlass(2)), List.of(oidc(2), breakGlass(2)));
        AdministratorContinuityGuard.Snapshot snapshot = guard.lockAndCapture("USER_STATUS", List.of(10L));
        assertThrows(BusinessException.class, () -> guard.verify(snapshot));
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();

        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verifyNoInteractions(auditLogService, transactionManager);

        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior());
        ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq("ops-operator"), eq("SECURITY"), uri.capture(), eq(400),
                eq(AdministratorContinuityGuard.AUDIT_APPLICATION_CODE), eq(false));
        assertEquals("/internal/admin-continuity/deny?operation=USER_STATUS&targets=10"
                + "&violations=OIDC_ADMINISTRATORS(before=3,after=2,required=3)", uri.getValue());
    }

    @Test
    void 起動時検査の判定が揃わなければ確認できないとして止める() {
        when(security.checkAdministratorConditions()).thenReturn(List.of(oidc(3), breakGlass(2)));
        AdministratorContinuityGuard securityOnly = guard(List.of(security));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> securityOnly.lockAndCapture("USER_STATUS", List.of(10L)));
        assertTrue(error.getMessage().contains("確認できません"), error.getMessage());
    }

    @Test
    void transactionの外では呼べない() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThrows(IllegalStateException.class, () -> guard.lockAndCapture("USER_STATUS", List.of(10L)));
        assertThrows(IllegalStateException.class, () -> guard.captureLocked("ENGINEER_DELETE", 10L));
        verifyNoInteractions(sysUserMapper, security);
    }

    private AdministratorContinuityGuard guard(List<AdministratorStartupConditions> sources) {
        return new AdministratorContinuityGuard(environment, sysUserMapper, provider(sources),
                provider(List.of(auditLogService)), provider(List.of(transactionManager)));
    }

    /** guardが同じtransactionの判定に付ける印（別のguardの判定を同じthreadで始めるために外す）。 */
    private static Object resourceKeyOf(AdministratorContinuityGuard guard) {
        return org.springframework.test.util.ReflectionTestUtils.getField(AdministratorContinuityGuard.class,
                "TRANSACTION_RESOURCE_KEY");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(List<T> beans) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> beans.stream());
        when(provider.getIfAvailable()).thenReturn(beans.isEmpty() ? null : beans.get(0));
        return provider;
    }

    private static SysUser user(long id, String tenantId) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        return user;
    }

    private static AdministratorConditionCheck oidc(int count) {
        return AdministratorConditionCheck.evaluated(Condition.OIDC_ADMINISTRATORS, count, 3, count >= 3);
    }

    private static AdministratorConditionCheck breakGlass(int count) {
        return AdministratorConditionCheck.evaluated(Condition.BREAK_GLASS_ADMINISTRATORS, count, 2, count == 2);
    }

    private static AdministratorConditionCheck privacy(int count) {
        return AdministratorConditionCheck.evaluated(Condition.PRIVACY_ADMINISTRATORS, count, 2, count >= 2);
    }

    private static AdministratorConditionCheck notEvaluated(Condition condition, int required) {
        return AdministratorConditionCheck.notEvaluated(condition, required);
    }
}
