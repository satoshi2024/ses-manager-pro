package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalDelegationTypeMapper;
import com.ses.mapper.ApprovalParticipantMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.approval.ApprovalNotificationService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.approval.ResolvedRoute;
import com.ses.service.approval.RouteResolverService;
import com.ses.service.approval.RouteStepGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1-07: target version競合時に古いsnapshotを適用せず、現在値から再申請する回帰。 */
@ExtendWith(MockitoExtension.class)
class ApprovalEngineConflictTest {

    @Mock ApprovalRequestMapper requestMapper;
    @Mock ApprovalActionMapper actionMapper;
    @Mock ApprovalDelegationMapper delegationMapper;
    @Mock ApprovalDelegationTypeMapper delegationTypeMapper;
    @Mock ApprovalParticipantMapper participantMapper;
    @Mock SysUserMapper userMapper;
    @Mock RouteResolverService routeResolver;
    @Mock NotificationService notificationService;
    @Mock ApprovalNotificationService approvalNotificationService;
    @Mock ApprovalTargetAdapter adapter;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<ApprovalAction> actions = new ArrayList<>();
    private ApprovalRequest storedRequest;
    private ApprovalEngineServiceImpl service;

    @BeforeEach
    void setUp() {
        when(adapter.supportedRequestTypes()).thenReturn(Set.of("quotation.submit"));
        when(adapter.currentVersion(42L)).thenReturn(2L);
        when(adapter.snapshot(eq(42L), anyMap())).thenReturn(new ApprovalSnapshot(
                3L, null, null, Map.of("fresh", "current"), Map.of()));
        when(routeResolver.resolve(eq("quotation.submit"), any(), any(), eq(10L), any(LocalDate.class)))
                .thenReturn(new ResolvedRoute(1L, 1, null,
                        List.of(new RouteStepGroup(1, null, List.of(20L)))));
        when(requestMapper.selectByIdForUpdate(1L)).thenAnswer(invocation -> storedRequest);
        when(requestMapper.update(any(), any())).thenReturn(1);
        doAnswer(invocation -> {
            ApprovalRequest request = invocation.getArgument(0);
            request.setId(1L);
            storedRequest = request;
            return 1;
        }).when(requestMapper).insert(any(ApprovalRequest.class));
        when(actionMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(actions));
        doAnswer(invocation -> {
            ApprovalAction action = invocation.getArgument(0);
            action.setId((long) actions.size() + 1L);
            actions.add(action);
            return 1;
        }).when(actionMapper).insert(any(ApprovalAction.class));

        service = new ApprovalEngineServiceImpl(requestMapper, actionMapper, delegationMapper,
                delegationTypeMapper, participantMapper, userMapper, routeResolver, notificationService,
                approvalNotificationService, objectMapper, List.of(adapter));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void targetVersion競合ではapplyせず現在値から再申請し金額nullもDB更新対象にする() {
        ApprovalRequest request = service.request(new ApprovalRequestCommand(
                "quotation.submit", "QUOTATION", 42L, 1L, 10L, null,
                BigDecimal.valueOf(100), Map.of("old", "snapshot"), Map.of("old", true), null));

        service.approve(request.getId(), 20L, "承認");

        assertEquals("conflict", request.getStatus());
        verify(adapter, never()).applyApproved(any());

        ApprovalRequest resubmitted = service.resubmit(request.getId(), 10L, null, null, null);

        assertEquals("in_review", resubmitted.getStatus());
        assertEquals(2, resubmitted.getRoundNo());
        assertEquals(3L, resubmitted.getTargetVersion());
        assertEquals(Map.of("fresh", "current"), readPayload(resubmitted.getPayloadJson()));
        assertNull(resubmitted.getAmountSnapshot());
        verify(adapter).snapshot(eq(42L), anyMap());

        ArgumentCaptor<UpdateWrapper<ApprovalRequest>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(requestMapper, org.mockito.Mockito.atLeast(3)).update(isNull(), captor.capture());
        UpdateWrapper<ApprovalRequest> resubmitUpdate = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertTrue(resubmitUpdate.getSqlSet().contains("amount_snapshot"));
        assertTrue(resubmitUpdate.getParamNameValuePairs().values().stream().anyMatch(value -> value == null));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
