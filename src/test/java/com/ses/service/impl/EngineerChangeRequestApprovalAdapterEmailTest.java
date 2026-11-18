package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerChangeRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerCareerMapper;
import com.ses.mapper.EngineerChangeRequestMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerService;
import com.ses.service.EngineerSkillService;
import com.ses.service.NotificationService;
import com.ses.service.changerequest.EngineerChangeRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * U7（NF01細粒度epoch計画 §4.1・§11 F-7、W3-15）: 変更申請の承認でemailを反映するとき、連携ユーザー行をX lockしてから
 * email列だけを更新し、非lockで読んだ全列を {@code updateById} で書き戻さないことを固定する
 * （読取と書込みの間に管理者が確定した無効化・role変更を古い値で戻さない）。
 */
class EngineerChangeRequestApprovalAdapterEmailTest {

    private static final long CHANGE_ID = 40L;
    private static final long ENGINEER_ID = 50L;
    private static final long USER_ID = 60L;

    private EngineerChangeRequestMapper changeRequestMapper;
    private EngineerMapper engineerMapper;
    private EngineerService engineerService;
    private EngineerAccountLinkService linkService;
    private SysUserMapper sysUserMapper;
    private EngineerChangeRequestApprovalAdapter adapter;

    @BeforeEach
    void setUp() {
        changeRequestMapper = mock(EngineerChangeRequestMapper.class);
        engineerMapper = mock(EngineerMapper.class);
        engineerService = mock(EngineerService.class);
        linkService = mock(EngineerAccountLinkService.class);
        sysUserMapper = mock(SysUserMapper.class);
        adapter = new EngineerChangeRequestApprovalAdapter(changeRequestMapper, engineerMapper,
                mock(EngineerSkillMapper.class), mock(EngineerCareerMapper.class), engineerService,
                mock(EngineerSkillService.class), linkService, mock(NotificationService.class), sysUserMapper,
                new ObjectMapper(), Clock.systemDefaultZone());

        EngineerChangeRequest change = new EngineerChangeRequest();
        change.setId(CHANGE_ID);
        change.setEngineerId(ENGINEER_ID);
        change.setRequestType(EngineerChangeRequestService.TYPE_PROFILE);
        change.setStatus(EngineerChangeRequestService.STATUS_APPLIED);
        change.setVersion(0);
        change.setPayloadJson("{\"email\":\"new-address@example.com\"}");
        when(changeRequestMapper.selectById(CHANGE_ID)).thenReturn(change);
        when(changeRequestMapper.update(isNull(), any())).thenReturn(1);

        Engineer engineer = new Engineer();
        engineer.setId(ENGINEER_ID);
        when(engineerMapper.selectById(ENGINEER_ID)).thenReturn(engineer);

        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(ENGINEER_ID);
        link.setSysUserId(USER_ID);
        when(linkService.findByEngineerId(ENGINEER_ID)).thenReturn(link);

        SysUser snapshot = new SysUser();
        snapshot.setId(USER_ID);
        snapshot.setEmail("old-address@example.com");
        snapshot.setStatus(1);
        snapshot.setRole("要員");
        when(sysUserMapper.selectById(USER_ID)).thenReturn(snapshot);
    }

    @Test
    void email反映は連携ユーザー行をlockしてemail列だけを更新し全列を書き戻さない() {
        SysUser locked = new SysUser();
        locked.setId(USER_ID);
        locked.setStatus(0); // 承認確定の直前に管理者が無効化した状態
        when(sysUserMapper.selectByIdForUpdate(USER_ID)).thenReturn(locked);
        when(sysUserMapper.update(isNull(), any())).thenReturn(1);

        adapter.applyApproved(approvedRequest());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<SysUser>> wrapper = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(sysUserMapper).update(isNull(), wrapper.capture());
        String sqlSet = wrapper.getValue().getSqlSet();
        assertTrue(sqlSet.startsWith("email="), sqlSet);
        assertFalse(sqlSet.contains(","), "email以外の列を書かないこと: " + sqlSet);
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue("new-address@example.com"));
        verify(sysUserMapper, never()).updateById(any(SysUser.class));

        // 承認確定の申請行（呼出元が保持）→ 連携ユーザー行（rank 3）→ 要員行（rank 7）の順。
        InOrder order = inOrder(sysUserMapper, engineerService);
        order.verify(sysUserMapper).selectByIdForUpdate(USER_ID);
        order.verify(sysUserMapper).update(isNull(), any());
        order.verify(engineerService).updateWithStatusGuard(any(Engineer.class));
    }

    @Test
    void 連携ユーザーが削除済みならemailを反映せず要員の反映は続ける() {
        when(sysUserMapper.selectByIdForUpdate(USER_ID)).thenReturn(null);

        adapter.applyApproved(approvedRequest());

        verify(sysUserMapper, never()).update(any(), any());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        verify(engineerService).updateWithStatusGuard(any(Engineer.class));
        assertEquals(1, org.mockito.Mockito.mockingDetails(changeRequestMapper).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("update")).count());
    }

    private ApprovalRequest approvedRequest() {
        ApprovalRequest request = new ApprovalRequest();
        request.setTargetId(CHANGE_ID);
        request.setTargetVersion(adapter.currentVersion(CHANGE_ID));
        return request;
    }
}
