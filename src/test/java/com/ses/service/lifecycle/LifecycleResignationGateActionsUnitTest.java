package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LifecycleCase;
import com.ses.entity.PortalUser;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetOffboardingService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.OrganizationService;
import com.ses.service.portal.PortalSessionService;
import com.ses.service.security.PersistentSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF01 batch 0: 退社ゲート自動処理の一貫性（ユーザー停止の部分列更新、担当営業解除のservice経由、
 * ポータルユーザー複数一致のfail-closed、他tenant連携ユーザーのfail-closed）。
 */
@ExtendWith(MockitoExtension.class)
class LifecycleResignationGateActionsUnitTest {

    private static final String TENANT = "tenant-a";
    private static final Long ENGINEER_ID = 20L;
    private static final Long USER_ID = 30L;

    @Mock private SysUserMapper sysUserMapper;
    @Mock private EngineerAccountLinkService engineerAccountLinkService;
    @Mock private EngineerSalesService engineerSalesService;
    @Mock private EngineerSalesMapper engineerSalesMapper;
    @Mock private PortalUserMapper portalUserMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private OrganizationService organizationService;
    @Mock private PersistentSessionService persistentSessionService;
    @Mock private PortalSessionService portalSessionService;
    @Mock private ExpenseRequestMapper expenseRequestMapper;
    @Mock private LifecycleTaskMapper lifecycleTaskMapper;
    @Mock private AssetOffboardingService assetOffboardingService;

    @InjectMocks
    private ResignationGateChecker checker;

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
    void 担当営業解除はEngineerSalesService経由で行いmapperへ直接書き込まない() {
        when(engineerAccountLinkService.findByEngineerId(ENGINEER_ID)).thenReturn(null);

        checker.executeAutomaticGateActions(resignationCase(), engineer());

        verify(engineerSalesService).releaseAllByEngineerId(ENGINEER_ID);
        verifyNoInteractions(engineerSalesMapper);
    }

    @Test
    void 案件tenantのポータルユーザーが複数一致したら失効も連携解除もせず409で中断する() {
        linkedUser(activeUser("dup@example.test"));
        when(sysUserMapper.update(isNull(), any())).thenReturn(1);
        PortalUser first = new PortalUser();
        first.setId(101L);
        PortalUser second = new PortalUser();
        second.setId(102L);
        when(portalUserMapper.selectList(any())).thenReturn(List.of(first, second));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.executeAutomaticGateActions(resignationCase(), engineer()));

        assertEquals(409, ex.getCode());
        assertEquals("error.lifecycle.resignationGateFailed", ex.getMessageKey());
        verify(portalSessionService, never()).revokeAllForUser(anyLong(), anyString());
        verify(engineerAccountLinkService, never()).unlinkByEngineerId(any());
        verify(engineerSalesService, never()).releaseAllByEngineerId(any());
    }

    @Test
    void 連携ユーザーが他tenantにだけ存在する場合は越境更新せず409で中断する() {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(ENGINEER_ID);
        link.setSysUserId(USER_ID);
        when(engineerAccountLinkService.findByEngineerId(ENGINEER_ID)).thenReturn(link);
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
        when(engineerAccountLinkService.findByEngineerId(ENGINEER_ID)).thenReturn(link);
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
                .anchorDate(LocalDate.of(2026, 11, 30))
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
