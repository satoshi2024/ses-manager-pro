package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.*;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.lifecycle.impl.LifecycleExceptionApprovalAdapter;
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
class LifecycleDomainServiceTest {

    @Autowired
    private LifecycleTemplateService templateService;

    @Autowired
    private LifecycleCaseService caseService;

    @Autowired
    private LifecycleTaskService taskService;

    @Autowired
    private LifecycleDagValidator dagValidator;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    @Autowired
    private EngineerSalesMapper engineerSalesMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Autowired
    private LifecycleCaseMapper caseMapper;

    @Autowired
    private LifecycleTaskMapper taskMapper;

    @Autowired
    private LifecycleEventMapper eventMapper;

    @Autowired
    private com.ses.mapper.ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private com.ses.mapper.DocumentMapper documentMapper;

    @Autowired
    private LifecycleExceptionApprovalAdapter approvalAdapter;

    private Engineer testEngineer;
    private SysUser adminUser;
    private SysUser hrUser;
    private SysUser salesUser;
    private SysUser engineerUser;
    private OrganizationUnit testOrg;

    @BeforeEach
    void setUp() {
        // テスト用組織
        testOrg = OrganizationUnit.builder()
                .code("ORG-TECH-01")
                .name("開発第1部")
                .type("DEPARTMENT")
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        organizationUnitMapper.insert(testOrg);

        // テスト用ユーザー
        adminUser = SysUser.builder()
                .username("admin_test_01")
                .password("pass")
                .realName("管理者一郎")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(adminUser);

        hrUser = SysUser.builder()
                .username("hr_test_01")
                .password("pass")
                .realName("人事花子")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hrUser);

        salesUser = SysUser.builder()
                .username("sales_test_01")
                .password("pass")
                .realName("営業次郎")
                .role("営業")
                .status(1)
                .build();
        sysUserMapper.insert(salesUser);

        engineerUser = SysUser.builder()
                .username("eng_test_01")
                .password("pass")
                .realName("要員三郎")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(engineerUser);

        // 組織マネージャー設定
        UserOrganization userOrg = UserOrganization.builder()
                .userId(adminUser.getId())
                .organizationId(testOrg.getId())
                .managerUserId(adminUser.getId())
                .primaryFlag(1)
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        userOrganizationMapper.insert(userOrg);

        // テスト用エンジニア
        testEngineer = Engineer.builder()
                .fullName("要員三郎")
                .status("稼動中")
                .employmentType("正社員")
                .build();
        testEngineer.setOrganizationId(testOrg.getId());
        engineerMapper.insert(testEngineer);

        // 要員アカウントリンク
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(testEngineer.getId());
        link.setSysUserId(engineerUser.getId());
        engineerAccountLinkMapper.insert(link);

        // 担当営業紐付け
        EngineerSales engSales = EngineerSales.builder()
                .engineerId(testEngineer.getId())
                .salesUserId(salesUser.getId())
                .primaryFlag(1)
                .assignedAt(LocalDate.now().minusMonths(1))
                .build();
        engineerSalesMapper.insert(engSales);
    }

    @Test
    @DisplayName("F2-1: DAG循環依存の検出と拒否（自己依存・循環依存）")
    void testDagCycleDetection() {
        // 正常なDAG
        LifecycleTemplateTaskDto t1 = LifecycleTemplateTaskDto.builder().taskCode("T1").taskName("Task 1").build();
        LifecycleTemplateTaskDto t2 = LifecycleTemplateTaskDto.builder().taskCode("T2").taskName("Task 2").predecessorTaskCodes(List.of("T1")).build();
        LifecycleTemplateTaskDto t3 = LifecycleTemplateTaskDto.builder().taskCode("T3").taskName("Task 3").predecessorTaskCodes(List.of("T2")).build();
        assertDoesNotThrow(() -> dagValidator.validateDtoDag(List.of(t1, t2, t3)));

        // 自己依存 T1 -> T1
        LifecycleTemplateTaskDto selfDep = LifecycleTemplateTaskDto.builder().taskCode("T1").taskName("Task 1").predecessorTaskCodes(List.of("T1")).build();
        assertThrows(BusinessException.class, () -> dagValidator.validateDtoDag(List.of(selfDep)));

        // 循環依存 T1 -> T2 -> T3 -> T1
        LifecycleTemplateTaskDto c1 = LifecycleTemplateTaskDto.builder().taskCode("T1").taskName("Task 1").predecessorTaskCodes(List.of("T3")).build();
        LifecycleTemplateTaskDto c2 = LifecycleTemplateTaskDto.builder().taskCode("T2").taskName("Task 2").predecessorTaskCodes(List.of("T1")).build();
        LifecycleTemplateTaskDto c3 = LifecycleTemplateTaskDto.builder().taskCode("T3").taskName("Task 3").predecessorTaskCodes(List.of("T2")).build();
        assertThrows(BusinessException.class, () -> dagValidator.validateDtoDag(List.of(c1, c2, c3)));
    }

    @Test
    @DisplayName("F2-2: テンプレート作成・改定と進行中案件の版保護")
    void testTemplateCreationAndVersionImmutability() {
        LifecycleTemplateDto tpl = LifecycleTemplateDto.builder()
                .templateType("JOIN")
                .name("正社員標準入社フロー")
                .description("入社手続き")
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("J1")
                                .taskName("書類提出")
                                .assigneeRule("ENGINEER_SELF")
                                .isMandatory(1)
                                .isBlocking(1)
                                .sortOrder(10)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("J2")
                                .taskName("PC手配")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .isMandatory(1)
                                .isBlocking(1)
                                .predecessorTaskCodes(List.of("J1"))
                                .sortOrder(20)
                                .build()
                ))
                .build();
        LifecycleTemplateDto created = templateService.createTemplate(tpl, adminUser.getId());
        assertNotNull(created.getId());
        assertEquals(1, created.getVersionNo());
        
        // Assert that taskCount is set in listTemplates
        List<LifecycleTemplateDto> allTpls = templateService.listTemplates("JOIN", "ACTIVE");
        assertTrue(allTpls.stream().anyMatch(t -> t.getId().equals(created.getId()) && t.getTaskCount() == 2), "taskCount must be correctly populated in list");
        
        // Assert invalidDateOrder for createTemplate
        LifecycleTemplateDto invalidTpl = LifecycleTemplateDto.builder()
                .templateType("JOIN").name("Bad").validFrom(LocalDate.now().plusDays(10)).validTo(LocalDate.now())
                .tasks(List.of(LifecycleTemplateTaskDto.builder().taskCode("B").taskName("B").assigneeRule("HR").sortOrder(10).build())).build();
        assertThrows(BusinessException.class, () -> templateService.createTemplate(invalidTpl, adminUser.getId()));

        // 案件を起票 (v1で起票される)
        CreateLifecycleCaseCommand cmd = CreateLifecycleCaseCommand.builder()
                .engineerId(testEngineer.getId())
                .lifecycleType("JOIN")
                .templateId(created.getId())
                .anchorDate(LocalDate.now())
                .build();
        LifecycleCaseDto caseDto = caseService.createCase(hrUser.getId(), cmd);
        assertEquals(1, caseDto.getTemplateVersion());

        // テンプレートを改定 (v2を作成)
        tpl.setName("正社員標準入社フロー (改定)");
        tpl.getTasks().get(0).setTaskName("書類提出(電子的)");
        LifecycleTemplateDto v2 = templateService.updateTemplate(created.getId(), tpl, adminUser.getId());
        assertEquals(2, v2.getVersionNo());
        
        // Check validFrom overlap adjustment logic
        LifecycleTemplate oldTpl = templateService.getById(created.getId());
        assertTrue(oldTpl.getValidTo().isBefore(v2.getValidFrom()), "Old version must end before new version begins");
        
        // Assert taskCount for v2
        List<LifecycleTemplateDto> allTplsV2 = templateService.listTemplates("JOIN", "ACTIVE");
        assertTrue(allTplsV2.stream().anyMatch(t -> t.getId().equals(v2.getId()) && t.getTaskCount() == 2), "taskCount must be correctly populated in list for v2");

        // 既存案件のバージョンが保護されていることを確認
        LifecycleCase lcCase = caseMapper.selectById(caseDto.getId());
        assertEquals(1, lcCase.getTemplateVersion(), "進行中案件のテンプレート版番号は改定によって変更されてはならない");
    }

    @Test
    @DisplayName("F2-3: 担当者自動解決（ROLE, PRIMARY_SALES, ENGINEER_SELF, ORG_MANAGER, APPLICANT）とタスク遷移")
    void testAssigneeResolutionAndTaskProgression() {
        LifecycleTemplateDto tpl = LifecycleTemplateDto.builder()
                .templateType("ASSIGNMENT")
                .name("現場配属フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("A1_SALES")
                                .taskName("案件条件合意")
                                .assigneeRule("PRIMARY_SALES")
                                .isMandatory(1)
                                .isBlocking(1)
                                .sortOrder(10)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("A2_SELF")
                                .taskName("現場入場前確認")
                                .assigneeRule("ENGINEER_SELF")
                                .isMandatory(1)
                                .isBlocking(1)
                                .predecessorTaskCodes(List.of("A1_SALES"))
                                .sortOrder(20)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("A3_HR")
                                .taskName("配属通知発行")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .isMandatory(1)
                                .isBlocking(1)
                                .predecessorTaskCodes(List.of("A2_SELF"))
                                .sortOrder(30)
                                .build()
                ))
                .build();
        LifecycleTemplateDto created = templateService.createTemplate(tpl, adminUser.getId());

        CreateLifecycleCaseCommand cmd = CreateLifecycleCaseCommand.builder()
                .engineerId(testEngineer.getId())
                .lifecycleType("ASSIGNMENT")
                .templateId(created.getId())
                .anchorDate(LocalDate.now())
                .build();
        LifecycleCaseDto caseDto = caseService.createCase(hrUser.getId(), cmd);

        List<LifecycleTaskDto> tasks = caseDto.getTasks();
        assertEquals(3, tasks.size());

        // A1_SALES は PRIMARY_SALES(salesUser) に割り当てられ、先行依存がないため IN_PROGRESS
        LifecycleTaskDto t1 = tasks.stream().filter(t -> "A1_SALES".equals(t.getTaskCode())).findFirst().orElseThrow();
        assertEquals(salesUser.getId(), t1.getAssigneeUserId());
        assertEquals("IN_PROGRESS", t1.getStatus());

        // A2_SELF は ENGINEER_SELF(engineerUser) に割り当てられ、A1未完了のため PENDING
        LifecycleTaskDto t2 = tasks.stream().filter(t -> "A2_SELF".equals(t.getTaskCode())).findFirst().orElseThrow();
        assertEquals(engineerUser.getId(), t2.getAssigneeUserId());
        assertEquals("PENDING", t2.getStatus());

        // A2 を先行完了なしで開始しようとするとエラー
        assertThrows(BusinessException.class, () -> taskService.startTask(t2.getId(), engineerUser.getId()));

        // A1 を営業が完了 -> A2 が自動的に IN_PROGRESS に昇格
        CompleteLifecycleTaskCommand compCmd = CompleteLifecycleTaskCommand.builder()
                .completionComment("契約締結完了")
                .build();
        taskService.completeTask(t1.getId(), salesUser.getId(), compCmd);

        LifecycleTask updatedT2 = taskMapper.selectById(t2.getId());
        assertEquals("IN_PROGRESS", updatedT2.getStatus(), "先行タスク完了に伴い後続タスクがIN_PROGRESSに昇格するはず");

        // A2 を要員本人が完了 -> A3 が IN_PROGRESS に昇格
        taskService.completeTask(t2.getId(), engineerUser.getId(), CompleteLifecycleTaskCommand.builder().completionComment("確認完了").build());
        LifecycleTaskDto t3 = tasks.stream().filter(t -> "A3_HR".equals(t.getTaskCode())).findFirst().orElseThrow();
        LifecycleTask updatedT3 = taskMapper.selectById(t3.getId());
        assertEquals("IN_PROGRESS", updatedT3.getStatus());

        // A3 を HR が完了 -> 案件を完了可能
        taskService.completeTask(t3.getId(), hrUser.getId(), CompleteLifecycleTaskCommand.builder().completionComment("通知発行完了").build());

        caseService.completeCase(caseDto.getId(), hrUser.getId());
        LifecycleCase completedCase = caseMapper.selectById(caseDto.getId());
        assertEquals("COMPLETED", completedCase.getStatus());
        assertNotNull(completedCase.getCompletedAt());
    }

    @Test
    @DisplayName("F2-4: 退社ゲート（ResignationGateChecker）と未完了阻害タスクのブロック")
    void testResignationGateAndBlockingTasks() {
        LifecycleTemplateDto tpl = LifecycleTemplateDto.builder()
                .templateType("RESIGNATION")
                .name("正社員退社フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_SALES_RELEASE")
                                .taskName("担当営業割当解除")
                                .assigneeRule("PRIMARY_SALES")
                                .isMandatory(1)
                                .isBlocking(1)
                                .sortOrder(10)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_ASSET_RETURN")
                                .taskName("貸与PC返却")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .isMandatory(1)
                                .isBlocking(1)
                                .sortOrder(20)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_DOC_RETENTION")
                                .taskName("秘密保持誓約書受理")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .isMandatory(1)
                                .isBlocking(1)
                                .evidenceType("DOCUMENT_LINK")
                                .sortOrder(30)
                                .build()
                ))
                .build();
        LifecycleTemplateDto created = templateService.createTemplate(tpl, adminUser.getId());

        CreateLifecycleCaseCommand cmd = CreateLifecycleCaseCommand.builder()
                .engineerId(testEngineer.getId())
                .lifecycleType("RESIGNATION")
                .templateId(created.getId())
                .anchorDate(LocalDate.now())
                .build();
        LifecycleCaseDto caseDto = caseService.createCase(hrUser.getId(), cmd);

        // 1. タスク未完了の状態で案件完了を試みる -> ブロックされる
        assertThrows(BusinessException.class, () -> caseService.completeCase(caseDto.getId(), hrUser.getId()),
                "未完了の完了阻害タスクが存在する場合は完了拒否されるはず");

        // 2. ゲート評価: アカウント有効 / 担当営業未解除 / 資産未返却で FAIL
        ResignationGateResultDto gateBefore = caseService.evaluateResignationGate(caseDto.getId(), hrUser);
        assertFalse(gateBefore.isPassed());

        // 3. タスクを完了していく
        List<LifecycleTaskDto> tasks = caseDto.getTasks();
        LifecycleTaskDto salesTask = tasks.stream().filter(t -> "RESIGN_SALES_RELEASE".equals(t.getTaskCode())).findFirst().orElseThrow();
        LifecycleTaskDto assetTask = tasks.stream().filter(t -> "RESIGN_ASSET_RETURN".equals(t.getTaskCode())).findFirst().orElseThrow();
        LifecycleTaskDto docTask = tasks.stream().filter(t -> "RESIGN_DOC_RETENTION".equals(t.getTaskCode())).findFirst().orElseThrow();

        // 営業割当を解除
        engineerSalesMapper.delete(new LambdaQueryWrapper<EngineerSales>().eq(EngineerSales::getEngineerId, testEngineer.getId()));
        taskService.completeTask(salesTask.getId(), salesUser.getId(), CompleteLifecycleTaskCommand.builder().completionComment("引継ぎ完了").build());

        // 貸与PC返却タスクを完了
        taskService.completeTask(assetTask.getId(), hrUser.getId(), CompleteLifecycleTaskCommand.builder().completionComment("PC・カード回収完了").build());

        // 誓約書文書を台帳に登録してタスク完了 (DOCUMENT_LINK 証跡)
        com.ses.entity.Document doc = new com.ses.entity.Document();
        doc.setTenantId("default");
        doc.setDocumentType("LIFECYCLE_EVIDENCE");
        doc.setTitle("秘密保持誓約書");
        doc.setDirection("INTERNAL");
        doc.setStatus("CONFIRMED");
        documentMapper.insert(doc);

        taskService.completeTask(docTask.getId(), hrUser.getId(), CompleteLifecycleTaskCommand.builder()
                .documentId(doc.getId())
                .completionComment("誓約書受領確認")
                .build());

        // ユーザーアカウントを無効化 (退社条件)
        engineerUser.setStatus(0);
        sysUserMapper.updateById(engineerUser);

        // 4. ゲート再評価 -> PASS
        ResignationGateResultDto gateAfter = caseService.evaluateResignationGate(caseDto.getId(), hrUser);
        assertTrue(gateAfter.isPassed(), "前提条件充足後は退社ゲートがPASSするはず: " + gateAfter.getSummary());

        // 5. 案件完了確定 -> 自動処理（組織閉鎖、セッション失効）実行と要員ステータス退社化
        caseService.completeCase(caseDto.getId(), hrUser.getId());

        LifecycleCase finalCase = caseMapper.selectById(caseDto.getId());
        assertEquals("COMPLETED", finalCase.getStatus());

        Engineer finalEngineer = engineerMapper.selectById(testEngineer.getId());
        assertEquals("Bench", finalEngineer.getStatus(), "退社案件完了に伴い要員ステータスが「Bench」に更新されるはず");
    }

    @Test
    @DisplayName("F2-5: 例外免除（ApprovalEngine連携）によるタスク免除と案件完了")
    void testTaskWaiveWithApproval() {
        LifecycleTemplateDto tpl = LifecycleTemplateDto.builder()
                .templateType("RESIGNATION")
                .name("退社例外テストフロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_ASSET_RETURN")
                                .taskName("私物回収")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .isMandatory(1)
                                .isBlocking(1)
                                .build()
                ))
                .build();
        LifecycleTemplateDto created = templateService.createTemplate(tpl, adminUser.getId());

        CreateLifecycleCaseCommand cmd = CreateLifecycleCaseCommand.builder()
                .engineerId(testEngineer.getId())
                .lifecycleType("RESIGNATION")
                .templateId(created.getId())
                .anchorDate(LocalDate.now())
                .build();
        LifecycleCaseDto caseDto = caseService.createCase(hrUser.getId(), cmd);
        LifecycleTaskDto task = caseDto.getTasks().get(0);

        // 1. バリデーション検証 (必須項目欠落時は例外)
        ApprovalSnapshot invalidSnapshot = approvalAdapter.snapshot(task.getId(), Map.of("reason", ""));
        assertThrows(BusinessException.class, () -> approvalAdapter.validateBeforeRequest(invalidSnapshot));

        ApprovalSnapshot validSnapshot = approvalAdapter.snapshot(task.getId(), Map.of(
                "reason", "私物なし確認済み",
                "riskOwner", "HR部長",
                "remedyDeadline", LocalDate.now().plusMonths(1).toString()
        ));
        assertDoesNotThrow(() -> approvalAdapter.validateBeforeRequest(validSnapshot));

        // 2. 承認申請なしの直接免除は拒否されること（SoD違反防止）
        BusinessException directEx = assertThrows(BusinessException.class, () ->
                taskService.waiveTask(task.getId(), adminUser.getId(), null, "私物残存なし"));
        assertEquals("error.lifecycle.waiveRequiresApproval", directEx.getMessageKey());

        // 3. 偽造・不一致承認IDによる免除も拒否されること
        BusinessException fakeEx = assertThrows(BusinessException.class, () ->
                taskService.waiveTask(task.getId(), adminUser.getId(), 999999L, "私物残存なし"));
        assertEquals("error.lifecycle.waiveRequiresApproval", fakeEx.getMessageKey());

        // 4. 正当な承認完了（ApprovalRequest APPROVED）経由でのみ WAIVED に遷移すること
        com.ses.entity.ApprovalRequest req = com.ses.entity.ApprovalRequest.builder()
                .requestNo("AR-LC-002")
                .requestType("LIFECYCLE_EXCEPTION")
                .targetType("LIFECYCLE_TASK")
                .targetId(task.getId())
                .targetVersion(0L)
                .applicantId(hrUser.getId())
                .routeSnapshotJson("[]")
                .status("APPROVED")
                .payloadJson("{\"reason\":\"私物なし確認済み\",\"riskOwner\":\"HR部長\",\"remedyDeadline\":\"" + LocalDate.now().plusMonths(1) + "\"}")
                .build();
        approvalRequestMapper.insert(req);

        approvalAdapter.applyApproved(req);

        LifecycleTask waivedTask = taskMapper.selectById(task.getId());
        assertEquals("WAIVED", waivedTask.getStatus());
        assertEquals(req.getId(), waivedTask.getApprovalRequestId());
        assertTrue(waivedTask.getCompletionComment().contains("私物なし確認済み"));
    }

    @Test
    @DisplayName("F2-6: スコープ境界（要員本人への非公開タスク隠蔽・案件ステータス編集拒否）")
    void testScopeBoundaryAndEngineerVisibility() {
        LifecycleTemplateDto tpl = LifecycleTemplateDto.builder()
                .templateType("LEAVE")
                .name("休職手続")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("L_PUBLIC")
                                .taskName("休職届提出")
                                .assigneeRule("ENGINEER_SELF")
                                .isEngineerVisible(1)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("L_INTERNAL")
                                .taskName("社内労務評価・産業医面談記録")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .isEngineerVisible(0) // 内部限定
                                .build()
                ))
                .build();
        LifecycleTemplateDto created = templateService.createTemplate(tpl, adminUser.getId());

        CreateLifecycleCaseCommand cmd = CreateLifecycleCaseCommand.builder()
                .engineerId(testEngineer.getId())
                .lifecycleType("LEAVE")
                .templateId(created.getId())
                .anchorDate(LocalDate.now())
                .build();
        LifecycleCaseDto caseDto = caseService.createCase(hrUser.getId(), cmd);

        // HRユーザーが取得した場合: 2件とも取得できる
        LifecycleCaseDto hrView = caseService.getCaseDetail(caseDto.getId(), hrUser);
        assertEquals(2, hrView.getTasks().size());

        // 要員本人が取得した場合: 本人公開タスクのみ1件取得できる (内部限定タスクは隠蔽)
        LifecycleCaseDto engView = caseService.getCaseDetail(caseDto.getId(), engineerUser);
        assertEquals(1, engView.getTasks().size());
        assertEquals("L_PUBLIC", engView.getTasks().get(0).getTaskCode());

        // 要員本人が案件ステータス変更（保留や完了）を試みると拒否される
        assertThrows(BusinessException.class, () -> caseService.holdCase(caseDto.getId(), engineerUser.getId(), "保留"));
    }
}
