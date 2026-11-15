package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LifecycleCase;
import com.ses.entity.PortalUserEngineerLink;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.PortalUserEngineerLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetOffboardingService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.OrganizationService;
import com.ses.service.portal.PortalSessionService;
import com.ses.service.security.PersistentSessionService;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF01 batch 0: 退社ゲート自動処理の一貫性（ユーザー停止の部分列更新、担当営業解除のservice経由、
 * ポータルsession失効は紐付け（V188）だけを対象にし紐付けのtenant不整合はfail-closed、他tenant連携ユーザーのfail-closed）。
 */
@ExtendWith(MockitoExtension.class)
class LifecycleResignationGateActionsUnitTest {

    private static final String TENANT = "tenant-a";
    private static final Long ENGINEER_ID = 20L;
    private static final Long USER_ID = 30L;
    /** 案件の退社基準日。日付なしoverload（本日付）ではなく、この値が渡されることを検証する。 */
    private static final LocalDate ANCHOR_DATE = LocalDate.of(2026, 11, 30);

    @Mock private SysUserMapper sysUserMapper;
    @Mock private com.ses.mapper.EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Mock private EngineerAccountLinkService engineerAccountLinkService;
    @Mock private EngineerSalesService engineerSalesService;
    @Mock private EngineerSalesMapper engineerSalesMapper;
    @Mock private PortalUserEngineerLinkMapper portalUserEngineerLinkMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private OrganizationService organizationService;
    @Mock private PersistentSessionService persistentSessionService;
    @Mock private PortalSessionService portalSessionService;
    @Mock private ExpenseRequestMapper expenseRequestMapper;
    @Mock private LifecycleTaskMapper lifecycleTaskMapper;
    @Mock private AssetOffboardingService assetOffboardingService;
    @Mock private AuthorizationEpochFence authorizationEpochFence;

    @InjectMocks
    private ResignationGateChecker checker;

    @Test
    void 自動処理はrank順に停止_所属終了_紐付け解除_営業解除を書き停止でactor_epochを進めsession失効は最後に行う() {
        linkedUser(activeUser(null));
        when(sysUserMapper.update(isNull(), any())).thenReturn(1);
        when(portalUserEngineerLinkMapper.selectActiveForEngineerInTenant(ENGINEER_ID, TENANT))
                .thenReturn(List.of(portalLink(301L)));

        checker.executeAutomaticGateActions(resignationCase(), engineer());

        // W3-16b（計画 §3.4・§3.5 規則5）: 3（停止）→ 8（所属）→ 9（紐付け）→ 10（担当営業）の後に葉（session）。
        InOrder order = inOrder(sysUserMapper, authorizationEpochFence, organizationService, engineerAccountLinkService,
                engineerSalesService, persistentSessionService, portalSessionService);
        order.verify(sysUserMapper).selectByIdAndTenantForUpdate(USER_ID, TENANT);
        order.verify(sysUserMapper).update(isNull(), any());
        order.verify(authorizationEpochFence).advanceIfChanged(AuthorizationEpochAnchor.actor(TENANT, USER_ID), 1, 0);
        order.verify(organizationService).closeAssignmentsForUser(USER_ID, ANCHOR_DATE);
        order.verify(engineerAccountLinkService).unlinkByEngineerId(ENGINEER_ID);
        order.verify(engineerSalesService).releaseAllByEngineerId(ENGINEER_ID, ANCHOR_DATE);
        order.verify(persistentSessionService).revokeAllForUser(eq(USER_ID), anyString());
        order.verify(portalSessionService).revokeAllForUser(eq(301L), anyString());
    }

    @Test
    void scope側の自動処理はsessionを失効させず対象だけを返し既に無効な連携ユーザーではactor_epochを停止で進めない() {
        SysUser disabled = activeUser(null);
        disabled.setStatus(0);
        linkedUser(disabled);
        when(portalUserEngineerLinkMapper.selectActiveForEngineerInTenant(ENGINEER_ID, TENANT))
                .thenReturn(List.of(portalLink(401L)));

        ResignationGateChecker.AutomaticActionResult result =
                checker.applyAutomaticScopeActions(resignationCase(), engineer());

        assertEquals(USER_ID, result.linkedUserId());
        assertEquals(List.of(401L), result.portalUserIds());
        verify(sysUserMapper, never()).update(any(), any());
        verifyNoInteractions(authorizationEpochFence, persistentSessionService, portalSessionService);
        verify(organizationService).closeAssignmentsForUser(USER_ID, ANCHOR_DATE);
        verify(engineerAccountLinkService).unlinkByEngineerId(ENGINEER_ID);

        checker.revokeSessionsAfterScopeActions(result);
        verify(persistentSessionService).revokeAllForUser(eq(USER_ID), anyString());
        verify(portalSessionService).revokeAllForUser(eq(401L), anyString());
    }

    @Test
    void 資産免除があっても法人不明の外部アカウントは退社ゲートを通さない() {
        LifecycleCase resignation = resignationCase();
        resignation.setId(100L);
        com.ses.entity.LifecycleTask asset = com.ses.entity.LifecycleTask.builder()
                .caseId(resignation.getId()).taskCode("RESIGN_ASSET_RETURN").status("WAIVED").build();
        asset.setId(101L);
        when(lifecycleTaskMapper.selectByCaseId(resignation.getId())).thenReturn(List.of(asset));
        when(assetOffboardingService.checkOffboardingClearanceForTenant(any(), any(), any(), any()))
                .thenReturn(com.ses.dto.asset.OffboardingClearanceResultDto.builder()
                        .waived(true).unregisterableAccountCount(2).unrevokedAccountCount(4).registeredAccountCount(1).build());
        var item = com.ses.service.accounting.AccountingTenantContextHolder.runWithTenant(TENANT,
                () -> checker.evaluate(resignation, engineer())).getItems().stream()
                .filter(result -> "ASSET_RETURN".equals(result.getCode())).findFirst().orElseThrow();
        assertFalse(item.isPassed());
        assertFalse(item.isWaived());
        assertTrue(item.getMessage().contains("完了時に失効要求を登録: 1件"));
        assertTrue(item.getMessage().contains("失効要求登録済み・確認待ち: 1件"));
    }

    @Test
    void 連携ユーザー停止はtenant限定の行ロック後にstatus列だけを更新する() {
        linkedUser(activeUser(null));
        when(sysUserMapper.update(isNull(), any())).thenReturn(1);

        checker.executeAutomaticGateActions(resignationCase(), engineer());

        verify(sysUserMapper).selectByIdAndTenantForUpdate(USER_ID, TENANT);
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SysUser>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sysUserMapper).update(isNull(), captor.capture());
        String sqlSet = ((UpdateWrapper<SysUser>) captor.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("status"), sqlSet);
        assertTrue(sqlSet.contains("updated_at"), sqlSet);
        for (String column : List.of("password", "role", "real_name", "email", "tenant_id", "username")) {
            assertFalse(sqlSet.contains(column + "="), "停止更新が " + column + " を書き戻さないこと: " + sqlSet);
        }
    }

    @Test
    void 担当営業解除は退社基準日付きでEngineerSalesService経由で行いmapperへ直接書き込まない() {
        when(engineerAccountLinkMapper.selectByEngineerIdAndTenant(ENGINEER_ID, TENANT)).thenReturn(null);

        checker.executeAutomaticGateActions(resignationCase(), engineer());

        // 解除日は処理日ではなく案件の退社基準日（組織所属の終了日と同じ基準）。
        verify(engineerSalesService).releaseAllByEngineerId(ENGINEER_ID, ANCHOR_DATE);
        verify(engineerSalesService, never()).releaseAllByEngineerId(any());
        verifyNoInteractions(engineerSalesMapper);
    }

    @Test
    void 組織所属の終了日と担当営業の解除日は同じ退社基準日を使う() {
        linkedUser(activeUser(null));
        when(sysUserMapper.update(isNull(), any())).thenReturn(1);

        checker.executeAutomaticGateActions(resignationCase(), engineer());

        verify(organizationService).closeAssignmentsForUser(USER_ID, ANCHOR_DATE);
        verify(engineerSalesService).releaseAllByEngineerId(ENGINEER_ID, ANCHOR_DATE);
    }

    @Test
    void ポータルユーザーの紐付けにtenant不整合があれば停止も失効も連携解除もせず409で中断する() {
        when(portalUserEngineerLinkMapper.countActiveScopeMismatchForEngineer(ENGINEER_ID, TENANT)).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.executeAutomaticGateActions(resignationCase(), engineer()));

        assertEquals(409, ex.getCode());
        assertEquals("error.lifecycle.resignationGateFailed", ex.getMessageKey());
        verify(sysUserMapper, never()).update(any(), any());
        verify(portalSessionService, never()).revokeAllForUser(anyLong(), anyString());
        verify(engineerAccountLinkService, never()).unlinkByEngineerId(any());
        verify(engineerSalesService, never()).releaseAllByEngineerId(any());
        verify(engineerSalesService, never()).releaseAllByEngineerId(any(), any());
    }

    @Test
    void ポータルsessionは紐付け済みのポータルユーザーだけを失効させemailでは照合しない() {
        linkedUser(activeUser("resign@example.test"));
        when(sysUserMapper.update(isNull(), any())).thenReturn(1);
        when(portalUserEngineerLinkMapper.selectActiveForEngineerInTenant(ENGINEER_ID, TENANT))
                .thenReturn(List.of(portalLink(101L), portalLink(102L)));

        checker.executeAutomaticGateActions(resignationCase(), engineer());

        verify(portalSessionService).revokeAllForUser(eq(101L), anyString());
        verify(portalSessionService).revokeAllForUser(eq(102L), anyString());
        verify(portalSessionService, times(2)).revokeAllForUser(anyLong(), anyString());
        verify(portalUserEngineerLinkMapper, never()).selectUnlinkedPortalUserIdsByEmailInTenant(any(), any());
    }

    @Test
    void 内部アカウント未連携の要員でも紐付け済みのポータルユーザーは失効させる() {
        when(engineerAccountLinkMapper.selectByEngineerIdAndTenant(ENGINEER_ID, TENANT)).thenReturn(null);
        when(portalUserEngineerLinkMapper.selectActiveForEngineerInTenant(ENGINEER_ID, TENANT))
                .thenReturn(List.of(portalLink(201L)));

        checker.executeAutomaticGateActions(resignationCase(), engineer());

        verify(portalSessionService).revokeAllForUser(eq(201L), anyString());
        verifyNoInteractions(persistentSessionService, organizationService);
    }

    private PortalUserEngineerLink portalLink(Long portalUserId) {
        PortalUserEngineerLink link = new PortalUserEngineerLink();
        link.setTenantId(TENANT);
        link.setPortalUserId(portalUserId);
        link.setEngineerId(ENGINEER_ID);
        return link;
    }

    @Test
    void 連携ユーザーが他tenantにだけ存在する場合は越境更新せず409で中断する() {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(ENGINEER_ID);
        link.setSysUserId(USER_ID);
        when(engineerAccountLinkMapper.selectByEngineerIdAndTenant(ENGINEER_ID, TENANT)).thenReturn(link);
        when(sysUserMapper.selectByIdAndTenantForUpdate(USER_ID, TENANT)).thenReturn(null);
        SysUser foreign = activeUser(null);
        foreign.setTenantId("tenant-b");
        when(sysUserMapper.selectById(USER_ID)).thenReturn(foreign);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.executeAutomaticGateActions(resignationCase(), engineer()));

        assertEquals(409, ex.getCode());
        verify(sysUserMapper, never()).update(any(), any());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        verifyNoInteractions(organizationService, persistentSessionService, portalSessionService);
    }

    @Test
    void 停止CASが0件ならそれ以降の撤権処理へ進まず409で中断する() {
        linkedUser(activeUser(null));
        when(sysUserMapper.update(isNull(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.executeAutomaticGateActions(resignationCase(), engineer()));

        assertEquals(409, ex.getCode());
        verify(organizationService, never()).closeAssignmentsForUser(eq(USER_ID), any());
    }

    private void linkedUser(SysUser user) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(ENGINEER_ID);
        link.setSysUserId(USER_ID);
        when(engineerAccountLinkMapper.selectByEngineerIdAndTenant(ENGINEER_ID, TENANT)).thenReturn(link);
        when(sysUserMapper.selectByIdAndTenantForUpdate(USER_ID, TENANT)).thenReturn(user);
    }

    private SysUser activeUser(String email) {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setTenantId(TENANT);
        user.setUsername("resigning");
        user.setPassword("secret");
        user.setRealName("退社予定者");
        user.setRole("要員");
        user.setEmail(email);
        user.setStatus(1);
        return user;
    }

    private LifecycleCase resignationCase() {
        return LifecycleCase.builder()
                .tenantId(TENANT)
                .lifecycleType("RESIGNATION")
                .engineerId(ENGINEER_ID)
                .anchorDate(ANCHOR_DATE)
                .status("ACTIVE")
                .build();
    }

    private Engineer engineer() {
        Engineer engineer = new Engineer();
        engineer.setId(ENGINEER_ID);
        engineer.setTenantId(TENANT);
        return engineer;
    }
}
