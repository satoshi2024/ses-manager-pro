package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.service.approval.RouteSnapshot;
import com.ses.service.approval.RouteStepGroup;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** A1: 可視性、field masking、差戻し再申請表示の直接回帰。 */
@ExtendWith(MockitoExtension.class)
class ApprovalViewServiceImplTest {
    @Mock ApprovalRequestMapper requestMapper;
    @Mock ApprovalActionMapper actionMapper;
    @Mock ApprovalDelegationMapper delegationMapper;
    @Mock AuthorizationService authorizationService;
    @Mock Authentication authentication;

    private ApprovalViewServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws Exception {
        service = new ApprovalViewServiceImpl(requestMapper, actionMapper, delegationMapper,
                objectMapper, authorizationService);
        lenient().when(actionMapper.selectList(any())).thenReturn(List.of());
        lenient().when(delegationMapper.selectList(any())).thenReturn(List.of());
        lenient().when(authorizationService.isAllowed(any(), eq("contract.cost.view"))).thenReturn(false);
        lenient().when(authorizationService.isAllowed(any(), eq("payroll.view"))).thenReturn(false);
        lenient().when(authorizationService.isAllowed(any(), eq("bp-company.view"))).thenReturn(false);
    }

    @Test
    void approverWithoutCostPermission_seesChangedOnlyAndNeverRawValues() throws Exception {
        ApprovalRequest request = request("in_review", 20L,
                "{\"cost\":{\"label\":\"原価\",\"before\":100,\"after\":200,\"changed\":true},\"title\":{\"label\":\"件名\",\"before\":\"A\",\"after\":\"B\"}}");
        when(requestMapper.selectById(1L)).thenReturn(request);

        var view = service.detail(1L, 20L, "営業", authentication);

        assertThat(view.diff()).anyMatch(d -> d.field().equals("cost") && d.masked()
                && d.before() == null && d.after() == null && d.changed());
        assertThat(view.diff()).anyMatch(d -> d.field().equals("title") && !d.masked()
                && "A".equals(d.before()) && "B".equals(d.after()));
        assertThat(view.canApprove()).isTrue();
    }

    @Test
    void bankAccountFieldは営業とマネージャーでマスクされ管理者で表示される() throws Exception {
        ApprovalRequest request = request("in_review", 20L,
                "{\"bankAccount\":{\"label\":\"口座番号\",\"before\":\"123\",\"after\":\"456\",\"changed\":true}}");
        when(requestMapper.selectById(4L)).thenReturn(request);

        when(authorizationService.isAllowed(authentication, "bp-company.bank-account.view"))
                .thenReturn(false);
        var salesView = service.detail(4L, 20L, "営業", authentication);
        var managerView = service.detail(4L, 20L, "マネージャー", authentication);
        assertThat(salesView.diff()).anyMatch(d -> d.field().equals("bankAccount")
                && d.masked() && d.before() == null && d.after() == null && d.changed());
        assertThat(managerView.diff()).anyMatch(d -> d.field().equals("bankAccount")
                && d.masked() && d.before() == null && d.after() == null && d.changed());

        when(authorizationService.isAllowed(authentication, "bp-company.bank-account.view"))
                .thenReturn(true);
        var adminView = service.detail(4L, 20L, "管理者", authentication);
        assertThat(adminView.diff()).anyMatch(d -> d.field().equals("bankAccount")
                && !d.masked() && "123".equals(d.before()) && "456".equals(d.after()));
    }

    @Test
    void returnedRequest_isVisibleToApplicantAndCanBeResubmitted() throws Exception {
        ApprovalRequest request = request("returned", 10L, "{\"title\":{\"before\":\"A\",\"after\":\"B\"}}");
        when(requestMapper.selectById(2L)).thenReturn(request);

        var view = service.detail(2L, 10L, "営業", authentication);

        assertThat(view.canResubmit()).isTrue();
        assertThat(view.canWithdraw()).isTrue();
        assertThat(view.canApprove()).isFalse();
    }

    @Test
    void unrelatedUser_cannotReadRequest() throws Exception {
        when(requestMapper.selectById(3L)).thenReturn(request("in_review", 10L, "{}"));

        assertThatThrownBy(() -> service.detail(3L, 99L, "営業", authentication))
                .isInstanceOf(BusinessException.class);
    }

    private ApprovalRequest request(String status, Long applicantId, String diff) throws Exception {
        String route = objectMapper.writeValueAsString(new RouteSnapshot(1L, 1, 1L,
                List.of(new RouteStepGroup(1, null, List.of(20L)))));
        ApprovalRequest result = ApprovalRequest.builder().requestNo("AR-1").requestType("CONTRACT")
                .targetType("CONTRACT").targetId(42L).targetVersion(1L).applicantId(applicantId)
                .organizationId(1L).payloadJson("{\"cost\":100}").diffJson(diff).routeSnapshotJson(route)
                .status(status).currentStep(1).requestedAt(LocalDateTime.now()).version(0).build();
        result.setId(1L);
        return result;
    }
}
