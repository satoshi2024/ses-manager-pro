package com.ses.service.lifecycle;

import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.NotificationService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.lifecycle.impl.LifecycleExceptionApprovalAdapter;
import com.ses.service.scheduler.LifecycleSlaScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleNotificationAndSlaTest {

    @Autowired
    private LifecycleTemplateService templateService;

    @Autowired
    private LifecycleCaseService caseService;

    @Autowired
    private LifecycleTaskService taskService;

    @Autowired
    private LifecycleSlaScheduler slaScheduler;

    @Autowired
    private LifecycleNotificationService notificationService;

    @Autowired
    private NotificationService coreNotificationService;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    @Autowired
    private com.ses.mapper.ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private com.ses.mapper.ApprovalActionMapper approvalActionMapper;

    @Autowired
    private LifecycleCaseMapper caseMapper;

    @Autowired
    private LifecycleTaskMapper taskMapper;

    @Autowired
    private LifecycleExceptionApprovalAdapter approvalAdapter;

    private SysUser adminUser;
    private SysUser hrUser;
    private Engineer engineer;
    private OrganizationUnit org;
    private LifecycleTemplateDto template;

    @BeforeEach
    void setUp() {
        adminUser = SysUser.builder()
                .username("admin_sla_test")
                .password("pass")
                .realName("管理者SLA")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(adminUser);

        hrUser = SysUser.builder()
                .username("hr_sla_test")
                .password("pass")
                .realName("人事SLA")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hrUser);

        org = OrganizationUnit.builder()
                .code("ORG-SLA-01")
                .name("開発部")
                .type("DEPARTMENT")
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        organizationUnitMapper.insert(org);

        UserOrganization uo = UserOrganization.builder()
                .userId(adminUser.getId())
                .organizationId(org.getId())
                .managerUserId(adminUser.getId())
                .primaryFlag(1)
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        userOrganizationMapper.insert(uo);

        engineer = Engineer.builder()
                .fullName("要員SLAテスト")
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineer.setOrganizationId(org.getId());
        engineerMapper.insert(engineer);

        // テンプレート作成 (タスク1: 相対期日-3日, タスク2: 相対期日+5日)
        template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("JOIN")
                .name("SLA監視フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("SLA_TASK_OVERDUE")
                                .taskName("期日超過タスク")
                                .assigneeRule("SPECIFIC_USER")
                                .assigneeRuleValue(hrUser.getId().toString())
                                .relativeDueDays(-3) // 基準日より3日前期日
                                .sortOrder(1)
                                .isMandatory(1)
                                .isBlocking(1)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("SLA_TASK_DUE_SOON")
                                .taskName("期日接近タスク")
                                .assigneeRule("SPECIFIC_USER")
                                .assigneeRuleValue(hrUser.getId().toString())
                                .relativeDueDays(1) // 基準日より1日後期日 (接近)
                                .sortOrder(2)
                                .isMandatory(1)
                                .isBlocking(1)
                                .build()
                ))
                .build(), adminUser.getId());
    }

    @Test
    @DisplayName("B1-1: SLAスケジューラーによる期日接近および超過タスクの検知と通知発行")
    void testSlaSchedulerAndNotifications() {
        // 案件起票 (基準日 = 本日)
        CreateLifecycleCaseCommand cmd = CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("JOIN")
                .templateId(template.getId())
                .anchorDate(LocalDate.now())
                .title("SLAテスト案件")
                .build();
        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), cmd);

        // SLAチェック実行
        int processed = slaScheduler.processSlaCheck(LocalDate.now());
        assertEquals(2, processed, "期日超過1件、期日接近1件の計2件が処理されるはず");

        // 通知テーブルに発行されたか確認
        List<Notification> notifications = notificationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>()
                        .eq(Notification::getRecipientUserId, hrUser.getId())
        );
        assertFalse(notifications.isEmpty(), "HRユーザー宛に通知が届いているはず");
        assertTrue(notifications.stream().anyMatch(n -> n.getType().contains("TASK_OVERDUE")), "超過通知が含まれるはず");
        assertTrue(notifications.stream().anyMatch(n -> n.getType().contains("TASK_DUE_SOON")), "接近通知が含まれるはず");
    }

    @Test
    @DisplayName("B1-2: 阻害タスクの例外承認アダプタ連携による免除と案件完了")
    void testExceptionApprovalAdapterFlow() {
        CreateLifecycleCaseCommand cmd = CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("JOIN")
                .templateId(template.getId())
                .anchorDate(LocalDate.now())
                .title("例外免除テスト案件")
                .build();
        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), cmd);

        LifecycleTask task1 = taskMapper.selectByCaseId(caseDto.getId()).get(0);
        LifecycleTask task2 = taskMapper.selectByCaseId(caseDto.getId()).get(1);

        // タスク1を完了
        taskService.completeTask(task1.getId(), hrUser.getId(), null);

        // タスク2（阻害タスク）が未完了の段階で案件完了を試みると例外発生
        assertThrows(Exception.class, () -> caseService.completeCase(caseDto.getId(), adminUser.getId()));

        // ApprovalSnapshot 作成
        ApprovalSnapshot snapshot = approvalAdapter.snapshot(task2.getId(), Map.of("reason", "役員承認による免除"));
        assertEquals("LIFECYCLE_EXCEPTION", approvalAdapter.requestType());
        assertNotNull(snapshot.payload());

        // 承認完了をシミュレート
        ApprovalRequest approvalReq = ApprovalRequest.builder()
                .requestNo("AR-LC-001")
                .requestType("LIFECYCLE_EXCEPTION")
                .targetType("LIFECYCLE_TASK")
                .targetId(task2.getId())
                .targetVersion(0L)
                .applicantId(hrUser.getId())
                .routeSnapshotJson("[]")
                .status("APPROVED")
                .payloadJson("{\"reason\":\"役員承認による免除\",\"riskOwner\":\"役員\",\"remedyDeadline\":\"" + LocalDate.now().plusMonths(1) + "\"}")
                .build();
        approvalRequestMapper.insert(approvalReq);

        approvalActionMapper.insert(ApprovalAction.builder()
                .requestId(approvalReq.getId())
                .roundNo(1)
                .stepNo(1)
                .slotIndex(0)
                .approverUserId(adminUser.getId())
                .approverSlotUserId(adminUser.getId())
                .action("APPROVE")
                .comment("承認")
                .actedAt(java.time.LocalDateTime.now())
                .build());

        // 承認確定実行
        approvalAdapter.applyApproved(approvalReq);

        // タスク2がWAIVEDになっていることを確認
        LifecycleTask updatedTask2 = taskMapper.selectById(task2.getId());
        assertEquals("WAIVED", updatedTask2.getStatus());
        assertEquals(approvalReq.getId(), updatedTask2.getApprovalRequestId());

        // 全阻害タスクがCOMPLETED/WAIVEDとなったため案件完了が成功すること
        assertDoesNotThrow(() -> caseService.completeCase(caseDto.getId(), adminUser.getId()));
        LifecycleCase finalCase = caseMapper.selectById(caseDto.getId());
        assertEquals("COMPLETED", finalCase.getStatus());
    }
}
