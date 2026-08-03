package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.Notification;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.approval.ApprovalSlaService;
import com.ses.service.scheduler.ApprovalSlaScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B1: 承認通知の宛先限定、route不足通知、SLA境界とscheduler冪等性。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalNotificationSlaTest {

    @Autowired private ApprovalEngineService approvalEngineService;
    @Autowired private ApprovalSlaService approvalSlaService;
    @Autowired private ApprovalSlaScheduler approvalSlaScheduler;
    @Autowired private ApprovalRouteMapper routeMapper;
    @Autowired private ApprovalRouteStepMapper stepMapper;
    @Autowired private ApprovalRequestMapper requestMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private OrganizationUnitMapper organizationUnitMapper;
    @Autowired private SysUserMapper userMapper;
    @Autowired private UserOrganizationMapper organizationMapper;

    private Long applicantId;
    private Long approverId;
    private Long outsiderId;
    private Long managerId;
    private Long organizationId;

    @BeforeEach
    void setUp() {
        applicantId = insertUser("b1-applicant");
        approverId = insertUser("b1-approver");
        outsiderId = insertUser("b1-outsider");
        managerId = insertUser("b1-manager");
        OrganizationUnit organization = OrganizationUnit.builder()
                .tenantId(1L).code("B1-" + System.nanoTime()).name("B1 test org")
                .type("部門").validFrom(LocalDate.now().minusDays(1)).status("有効").build();
        organizationUnitMapper.insert(organization);
        organizationId = organization.getId();
    }

    private Long insertUser(String prefix) {
        SysUser user = SysUser.builder()
                .username(prefix + "-" + System.nanoTime())
                .password("x").realName(prefix).role("管理者").status(1).build();
        userMapper.insert(user);
        return user.getId();
    }

    private void insertRoute(String requestType, Integer slaHours, Long approver) {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType(requestType).organizationId(null)
                .minAmount(null).maxAmount(null).versionNo(1)
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1).build();
        routeMapper.insert(route);
        stepMapper.insert(ApprovalRouteStep.builder()
                .routeId(route.getId()).stepNo(1).parallelGroup(1)
                .approverType("USER").approverValue(String.valueOf(approver))
                .slaHours(slaHours).build());
    }

    private ApprovalRequest request(String requestType) {
        return approvalEngineService.request(new ApprovalRequestCommand(
                requestType, "TEST", 1L, 1L, applicantId, null, BigDecimal.valueOf(1000),
                Map.of("k", "v"), null, null));
    }

    private List<Notification> notifications(String type) {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, type));
    }

    @Test
    void 申請通知は現在stepの承認者だけへ届く() {
        String type = "b1.requested." + System.nanoTime();
        insertRoute(type, null, approverId);
        ApprovalRequest request = request(type);

        List<Notification> requested = notifications("APPROVAL_REQUESTED").stream()
                .filter(n -> n.getDedupeKey().contains("approval-requested:" + request.getId()))
                .toList();
        assertEquals(1, requested.size());
        assertEquals(approverId, requested.get(0).getRecipientUserId());
        assertTrue(requested.stream().noneMatch(n -> outsiderId.equals(n.getRecipientUserId())));
        assertTrue(requested.stream().noneMatch(n -> applicantId.equals(n.getRecipientUserId())));
    }

    @Test
    void 申請差戻し承認却下は申請者だけへ届く() {
        String returnedType = "b1.return." + System.nanoTime();
        insertRoute(returnedType, null, approverId);
        ApprovalRequest returned = request(returnedType);
        approvalEngineService.returnForRevision(returned.getId(), approverId, "修正してください");

        String rejectedType = "b1.reject." + System.nanoTime();
        insertRoute(rejectedType, null, approverId);
        ApprovalRequest rejected = request(rejectedType);
        approvalEngineService.reject(rejected.getId(), approverId, "却下理由");

        String approvedType = "b1.approve." + System.nanoTime();
        insertRoute(approvedType, null, approverId);
        ApprovalRequest approved = request(approvedType);
        approvalEngineService.approve(approved.getId(), approverId, "承認");

        assertTrue(notifications("APPROVAL_RETURNED").stream().anyMatch(n -> applicantId.equals(n.getRecipientUserId())));
        assertTrue(notifications("APPROVAL_REJECTED").stream().anyMatch(n -> applicantId.equals(n.getRecipientUserId())));
        assertTrue(notifications("APPROVAL_APPROVED").stream().anyMatch(n -> applicantId.equals(n.getRecipientUserId())));
        assertTrue(notifications("APPROVAL_RETURNED").stream().noneMatch(n -> outsiderId.equals(n.getRecipientUserId())));
    }

    @Test
    void route未設定通知は申請rollback後も管理者へ残る() {
        String type = "b1.no-route." + System.nanoTime();
        BusinessException failure = assertThrows(BusinessException.class, () -> request(type));
        assertEquals("error.approval.noRouteMatch", failure.getMessageKey());
        assertTrue(notifications("APPROVAL_CONFIG_GAP").stream()
                .anyMatch(n -> n.getRecipientUserId() != null
                        && ("approval-config-gap-" + type + "-null")
                        .equals(n.getDedupeKey().replace("#u" + n.getRecipientUserId(), ""))));
    }

    @Test
    void SLAは直前とちょうどを除外し直後だけ上長へ一度通知する() {
        String type = "b1.sla." + System.nanoTime();
        insertRoute(type, 2, approverId);
        organizationMapper.insert(UserOrganization.builder()
                .userId(approverId).organizationId(organizationId).managerUserId(managerId)
                .primaryFlag(1).validFrom(LocalDate.now().minusDays(1)).build());
        ApprovalRequest request = request(type);
        LocalDateTime fixtureDeadline = LocalDateTime.now().plusHours(2);
        requestMapper.update(null, new UpdateWrapper<ApprovalRequest>()
                .eq("id", request.getId())
                .set("current_step_started_at", fixtureDeadline.minusHours(2)));
        // H2/MySQLのDATETIME精度で保存後に丸められるため、実際に保存された開始時刻から境界を作る。
        LocalDateTime deadline = requestMapper.selectById(request.getId())
                .getCurrentStepStartedAt().plusHours(2);

        assertEquals(0, approvalSlaScheduler.processOverdue(deadline.minusSeconds(1)));
        assertEquals(0, approvalSlaScheduler.processOverdue(deadline));
        approvalSlaScheduler.processOverdue(deadline.plusSeconds(1));
        approvalSlaScheduler.processOverdue(deadline.plusSeconds(1));

        List<Notification> escalations = notifications("APPROVAL_SLA_ESCALATED").stream()
                .filter(n -> n.getDedupeKey().contains("approval-sla-overdue:" + request.getId()))
                .toList();
        assertEquals(1, escalations.size());
        assertEquals(managerId, escalations.get(0).getRecipientUserId());
        assertTrue(escalations.stream().noneMatch(n -> outsiderId.equals(n.getRecipientUserId())));
    }

    @Test
    void 再申請roundはRETURNED_REQUESTED_SLAのdedupeを分離する() {
        String type = "b1.round-dedupe." + System.nanoTime();
        insertRoute(type, 1, approverId);
        organizationMapper.insert(UserOrganization.builder()
                .userId(approverId).organizationId(organizationId).managerUserId(managerId)
                .primaryFlag(1).validFrom(LocalDate.now().minusDays(1)).build());

        ApprovalRequest first = request(type);
        requestMapper.update(null, new UpdateWrapper<ApprovalRequest>()
                .eq("id", first.getId())
                .set("current_step_started_at", LocalDateTime.now().minusHours(2)));
        approvalSlaService.escalateOverdue(LocalDateTime.now());
        approvalEngineService.returnForRevision(first.getId(), approverId, "修正してください");

        ApprovalRequest second = approvalEngineService.resubmit(first.getId(), applicantId,
                Map.of("k", "round2"), Map.of("k", Map.of("before", "v", "after", "round2")),
                BigDecimal.valueOf(2000));
        requestMapper.update(null, new UpdateWrapper<ApprovalRequest>()
                .eq("id", second.getId())
                .set("current_step_started_at", LocalDateTime.now().minusHours(2)));
        approvalSlaService.escalateOverdue(LocalDateTime.now());

        List<Notification> requested = notifications("APPROVAL_REQUESTED").stream()
                .filter(n -> n.getDedupeKey().contains("approval-requested:" + first.getId()))
                .toList();
        assertEquals(2, requested.size());
        assertTrue(requested.stream().anyMatch(n -> n.getDedupeKey().contains(":round:1:step:1")));
        assertTrue(requested.stream().anyMatch(n -> n.getDedupeKey().contains(":round:2:step:1")));

        List<Notification> returned = notifications("APPROVAL_RETURNED").stream()
                .filter(n -> n.getDedupeKey().contains("approval-returned:" + first.getId()))
                .toList();
        assertEquals(1, returned.size());
        assertTrue(returned.get(0).getDedupeKey().contains(":round:1:step:1"));

        List<Notification> escalations = notifications("APPROVAL_SLA_ESCALATED").stream()
                .filter(n -> n.getDedupeKey().contains("approval-sla-overdue:" + first.getId()))
                .toList();
        assertEquals(2, escalations.size());
        assertTrue(escalations.stream().anyMatch(n -> n.getDedupeKey().contains(":round:1:step:1")));
        assertTrue(escalations.stream().anyMatch(n -> n.getDedupeKey().contains(":round:2:step:1")));
    }

    @Test
    void NULLのSLAは期限超過対象外() {
        String type = "b1.no-sla." + System.nanoTime();
        insertRoute(type, null, approverId);
        organizationMapper.insert(UserOrganization.builder()
                .userId(approverId).organizationId(organizationId).managerUserId(managerId)
                .primaryFlag(1).validFrom(LocalDate.now().minusDays(1)).build());
        ApprovalRequest request = request(type);
        requestMapper.update(null, new UpdateWrapper<ApprovalRequest>()
                .eq("id", request.getId())
                .set("current_step_started_at", LocalDateTime.now().minusDays(10)));

        approvalSlaService.escalateOverdue(LocalDateTime.now());
        assertTrue(notifications("APPROVAL_SLA_ESCALATED").stream()
                .noneMatch(n -> n.getDedupeKey().contains("approval-sla-overdue:" + request.getId())));
    }
}
