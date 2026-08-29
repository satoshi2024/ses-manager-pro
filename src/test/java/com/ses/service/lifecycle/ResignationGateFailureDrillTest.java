package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.AssetOffboardingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 退社統制ゲート障害訓練・網羅的ブロック検証テスト (Task M)
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResignationGateFailureDrillTest {

    @Autowired
    private LifecycleTemplateService templateService;

    @Autowired
    private LifecycleCaseService caseService;

    @Autowired
    private LifecycleTaskService taskService;

    @Autowired
    private ResignationGateChecker resignationGateChecker;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Autowired
    private com.ses.service.EngineerAccountLinkService engineerAccountLinkService;

    @Autowired
    private EngineerSalesMapper engineerSalesMapper;

    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private LifecycleCaseMapper caseMapper;

    @Autowired
    private LifecycleTaskMapper taskMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private LifecycleScopeService scopeService;

    @Autowired
    private LifecycleEventMapper eventMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetAssignmentMapper assetAssignmentMapper;

    @Autowired
    private ExternalAccountSystemMapper externalAccountSystemMapper;

    @Autowired
    private ExternalAccountReferenceMapper externalAccountReferenceMapper;

    @Autowired
    private LicensePlanMapper licensePlanMapper;

    @Autowired
    private LicenseAssignmentMapper licenseAssignmentMapper;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private AssetOffboardingService assetOffboardingService;

    private SysUser adminUser;
    private SysUser salesUser;
    private SysUser engineerUser;
    private Engineer engineer;
    private OrganizationUnit org;
    private LifecycleTemplateDto resignationTemplate;

    @BeforeEach
    void setUp() {
        adminUser = SysUser.builder()
                .username("admin_drill")
                .password("pass")
                .realName("管理者ドリル")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(adminUser);

        salesUser = SysUser.builder()
                .username("sales_drill")
                .password("pass")
                .realName("営業ドリル")
                .role("営業")
                .status(1)
                .build();
        sysUserMapper.insert(salesUser);

        SysUser hrUser = SysUser.builder()
                .username("hr_drill")
                .password("pass")
                .realName("人事ドリル")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hrUser);

        engineerUser = SysUser.builder()
                .username("eng_drill")
                .password("pass")
                .realName("退職要員")
                .role("要員")
                .status(1) // 初期状態: 有効
                .build();
        sysUserMapper.insert(engineerUser);

        org = OrganizationUnit.builder()
                .code("ORG-DRILL")
                .name("システム開発本部")
                .type("DEPARTMENT")
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        organizationUnitMapper.insert(org);

        engineer = Engineer.builder()
                .fullName("退職要員")
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineer.setOrganizationId(org.getId());
        engineerMapper.insert(engineer);

        // 要員とユーザーの紐付け
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineer.getId());
        link.setSysUserId(engineerUser.getId());
        engineerAccountLinkMapper.insert(link);

        // 主担当営業の紐付け (アクティブ)
        EngineerSales es = EngineerSales.builder()
                .engineerId(engineer.getId())
                .salesUserId(salesUser.getId())
                .primaryFlag(1)
                .assignedAt(LocalDate.now().minusMonths(6))
                .build();
        engineerSalesMapper.insert(es);

        // 組織所属 (アクティブ)
        UserOrganization uo = UserOrganization.builder()
                .userId(engineerUser.getId())
                .organizationId(org.getId())
                .managerUserId(adminUser.getId())
                .primaryFlag(1)
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        userOrganizationMapper.insert(uo);

        // 退社テンプレート (阻害タスク2件)
        resignationTemplate = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("RESIGNATION")
                .name("標準退社フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_ASSET_RETURN")
                                .taskName("PC・セキュリティカード返却確認")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(1)
                                .isMandatory(1)
                                .isBlocking(1)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_EXPENSE_SETTLE")
                                .taskName("立替経費・精算完了確認")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(2)
                                .isMandatory(1)
                                .isBlocking(1)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_DOC_RETENTION")
                                .taskName("退職届・誓約書保管確認")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(3)
                                .isMandatory(1)
                                .isBlocking(1)
                                .build()
                ))
                .build(), adminUser.getId());
    }

    @Test
    @DisplayName("M-1: 阻害タスク未完了による退社案件完了ブロック検証")
    void testResignationBlockedByUncompletedTasks() {
        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("RESIGNATION")
                .templateId(resignationTemplate.getId())
                .anchorDate(LocalDate.now())
                .title("退社手続き障害訓練")
                .build());

        // タスクが未完了の状態で完了を試みる
        BusinessException ex = assertThrows(BusinessException.class, () ->
                caseService.completeCase(caseDto.getId(), adminUser.getId()));
        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.blockingTasksUncompleted", ex.getMessageKey());
    }

    @Test
    @DisplayName("M-1-B: 退社gateが3大blocker台帳を直接照合し、承認済み例外だけを許可する")
    void testResignationGateUsesAssetOffboardingBlockersAndPersistedWaiver() {
        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("RESIGNATION")
                .templateId(resignationTemplate.getId())
                .anchorDate(LocalDate.now())
                .title("3大blocker連携検証")
                .build());
        LifecycleTask assetTask = taskMapper.selectByCaseId(caseDto.getId()).stream()
                .filter(t -> "RESIGN_ASSET_RETURN".equals(t.getTaskCode()))
                .findFirst().orElseThrow();

        Asset asset = Asset.builder()
                .assetTag("AST-GATE-BLOCKER-" + System.nanoTime())
                .assetName("Gate Blocker PC")
                .category("PC")
                .status("ASSIGNED")
                .build();
        assetMapper.insert(asset);
        assetAssignmentMapper.insert(AssetAssignment.builder()
                .assetId(asset.getId()).assigneeType("ENGINEER").assigneeId(engineer.getId())
                .startDate(LocalDate.now().minusDays(10)).status("ACTIVE").build());

        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("GATE-BLOCKER-SYS-" + System.nanoTime())
                .systemName("Gate Blocker SaaS").systemType("SAAS_SCM").isActive(1).build();
        externalAccountSystemMapper.insert(system);
        externalAccountReferenceMapper.insert(ExternalAccountReference.builder()
                .systemId(system.getId()).accountIdentifier("gate.blocker@example.jp")
                .assigneeType("ENGINEER").assigneeId(engineer.getId()).status("ACTIVE").build());

        LicensePlan plan = LicensePlan.builder()
                .planCode("GATE-BLOCKER-LIC-" + System.nanoTime()).planName("Gate Blocker License")
                .seatLimit(1).allocatedCount(1).status("ACTIVE").build();
        licensePlanMapper.insert(plan);
        licenseAssignmentMapper.insert(LicenseAssignment.builder()
                .planId(plan.getId()).assigneeType("ENGINEER").assigneeId(engineer.getId())
                .assignedDate(LocalDate.now()).status("ACTIVE").build());

        ResignationGateResultDto blocked = resignationGateChecker.evaluate(
                caseMapper.selectById(caseDto.getId()), engineer);
        ResignationGateResultDto.GateItemResult blockedAsset = blocked.getItems().stream()
                .filter(i -> "ASSET_RETURN".equals(i.getCode())).findFirst().orElseThrow();
        assertFalse(blockedAsset.isPassed());
        assertTrue(blockedAsset.getMessage().contains("blocker"));

        ApprovalRequest approval = ApprovalRequest.builder()
                .requestNo("AR-GATE-BLOCKER-" + System.nanoTime())
                .requestType("LIFECYCLE_EXCEPTION").targetType("LIFECYCLE_TASK")
                .targetId(assetTask.getId()).targetVersion(0L).applicantId(adminUser.getId())
                .payloadJson("{\"reason\":\"経営承認済み\",\"riskOwner\":\"HR\",\"remedyDeadline\":\""
                        + LocalDate.now().plusMonths(1) + "\"}")
                .routeSnapshotJson("[]").status("APPROVED").version(1).build();
        approvalRequestMapper.insert(approval);
        assetOffboardingService.approveOffboardingWaiver(
                engineer.getId(), "経営承認済み", approval.getId(), adminUser.getId());
        taskService.waiveTask(assetTask.getId(), adminUser.getId(), approval.getId(), "経営承認済み");

        ResignationGateResultDto waived = resignationGateChecker.evaluate(
                caseMapper.selectById(caseDto.getId()), engineer);
        ResignationGateResultDto.GateItemResult waivedAsset = waived.getItems().stream()
                .filter(i -> "ASSET_RETURN".equals(i.getCode())).findFirst().orElseThrow();
        assertTrue(waivedAsset.isPassed());
        assertTrue(waivedAsset.isWaived());
    }

    @Test
    @DisplayName("M-2: 全タスク完了後の退社ゲート自動クリーンアップと正常完了検証")
    void testResignationGateAutomaticCleanupAndCompletion() {
        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("RESIGNATION")
                .templateId(resignationTemplate.getId())
                .anchorDate(LocalDate.now())
                .title("退社手続き正常完了検証")
                .build());

        // 全タスクを完了
        List<LifecycleTask> tasks = taskMapper.selectByCaseId(caseDto.getId());
        for (LifecycleTask task : tasks) {
            taskService.completeTask(task.getId(), adminUser.getId(), null);
        }

        // 退社案件を完了確定
        assertDoesNotThrow(() -> caseService.completeCase(caseDto.getId(), adminUser.getId()));

        // 1. 案件ステータスが COMPLETED
        LifecycleCase finalCase = caseMapper.selectById(caseDto.getId());
        assertEquals("COMPLETED", finalCase.getStatus());
        assertNotNull(finalCase.getCompletedAt());

        // 2. 要員ステータスが Bench
        Engineer updatedEngineer = engineerMapper.selectById(engineer.getId());
        assertEquals("Bench", updatedEngineer.getStatus());

        // 3. ユーザーアカウントが無効化 (status = 0)
        SysUser updatedUser = sysUserMapper.selectById(engineerUser.getId());
        assertEquals(0, updatedUser.getStatus(), "退社完了によりユーザーアカウントが無効化されていること");

        // 4. 主担当営業が解放されていること (releasedAt != null)
        List<EngineerSales> salesLinks = engineerSalesMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerSales>()
                        .eq(EngineerSales::getEngineerId, engineer.getId())
        );
        for (EngineerSales es : salesLinks) {
            assertNotNull(es.getReleasedAt(), "主担当営業の紐付けが自動終了されていること");
        }

        // 5. 組織所属が終了されていること (validTo != null)
        List<UserOrganization> userOrgs = userOrganizationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserOrganization>()
                        .eq(UserOrganization::getUserId, engineerUser.getId())
        );
        for (UserOrganization uo : userOrgs) {
            assertNotNull(uo.getValidTo(), "組織所属が自動終了されていること");
        }

        // 6. ポータル連携が解除されていること
        EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineer.getId());
        assertNull(link, "退社完了により要員アカウント連携が解除されていること");
    }

    @Test
    @DisplayName("M-3: 必須ゲートタスクコード欠落によるFail-Closed検証 (LC-P0-03)")
    void testResignationBlockedByMissingRequiredTaskCode() {
        // RESIGN_DOC_RETENTION を欠落させた不完全な退社テンプレートを作成
        LifecycleTemplateDto incompleteTpl = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("RESIGNATION")
                .name("不完全退社フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_ASSET_RETURN")
                                .taskName("PC返却")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(1)
                                .isMandatory(1)
                                .isBlocking(1)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_EXPENSE_SETTLE")
                                .taskName("精算確認")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(2)
                                .isMandatory(1)
                                .isBlocking(1)
                                .build()
                ))
                .build(), adminUser.getId());

        // 新規要員で案件起票
        Engineer testEng2 = Engineer.builder().fullName("退職要員2").status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(testEng2);

        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(testEng2.getId())
                .lifecycleType("RESIGNATION")
                .templateId(incompleteTpl.getId())
                .anchorDate(LocalDate.now())
                .title("不完全テンプレ退社案件")
                .build());

        // 全タスクを完了
        List<LifecycleTask> tasks = taskMapper.selectByCaseId(caseDto.getId());
        for (LifecycleTask task : tasks) {
            taskService.completeTask(task.getId(), adminUser.getId(), null);
        }

        // RESIGN_DOC_RETENTION が欠落しているため、完了確定は 400 Bad Request で拒否されること
        BusinessException ex = assertThrows(BusinessException.class, () ->
                caseService.completeCase(caseDto.getId(), adminUser.getId()));
        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.resignationGateFailed", ex.getMessageKey());
    }

    @Test
    @DisplayName("M-4: 稼働中契約残存によるゲートFAIL検証 (LC-P1-09)")
    void testResignationBlockedByActiveContract() {
        // 稼働中の契約を作成
        Engineer testEng = Engineer.builder().fullName("退職要員3").status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(testEng);

        // t_contractに稼働中の契約を挿入（最低限の必須フィールドのみ）
        Contract contract = new Contract();
        contract.setEngineerId(testEng.getId());
        contract.setProjectId(1L);
        contract.setCustomerId(1L);
        contract.setStartDate(LocalDate.now().minusMonths(3));
        contract.setSellingPrice(BigDecimal.valueOf(500000));
        contract.setCostPrice(BigDecimal.valueOf(400000));
        contract.setStatus("稼動中");
        contract.setAcceptanceRequired(true);
        contractMapper.insert(contract);

        LifecycleCase lcCase = LifecycleCase.builder()
                .caseNo("LC-TEST-9999")
                .lifecycleType("RESIGNATION")
                .engineerId(testEng.getId())
                .templateId(1L)
                .templateVersion(1)
                .anchorDate(LocalDate.now())
                .status("ACTIVE")
                .title("稼働中契約テスト")
                .applicantUserId(adminUser.getId())
                .engineerSnapshotJson("{}")
                .version(0)
                .build();
        // caseMapperではなくResignationGateCheckerを直接呼ぶ
        // LC-P1-09: ACTIVE_CONTRACT ゲート項目が FAIL を返すこと
        ResignationGateResultDto result = resignationGateChecker.evaluate(lcCase, testEng);
        boolean activeContractFailed = result.getItems().stream()
                .filter(i -> "ACTIVE_CONTRACT".equals(i.getCode()))
                .anyMatch(i -> !i.isPassed());
        assertTrue(activeContractFailed, "稼働中契約が残存している場合、ACTIVE_CONTRACTゲートはFAILであること");
        assertFalse(result.isPassed(), "稼働中契約残存時はゲート全体がFAILであること");
    }

    @Test
    @DisplayName("M-5: reassignTask - COMPLETED案件のタスク担当変更ブロック検証 (LC-P1-05)")
    void testReassignTaskBlockedOnCompletedCase() {
        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("RESIGNATION")
                .templateId(resignationTemplate.getId())
                .anchorDate(LocalDate.now())
                .title("担当変更テスト退社")
                .build());

        // 全タスクを完了して退社ゲートをPASS
        List<LifecycleTask> tasks = taskMapper.selectByCaseId(caseDto.getId());
        for (LifecycleTask task : tasks) {
            taskService.completeTask(task.getId(), adminUser.getId(), null);
        }
        caseService.completeCase(caseDto.getId(), adminUser.getId());

        // COMPLETED案件のタスクに対して担当変更を試みる
        LifecycleTask completedTask = taskMapper.selectByCaseId(caseDto.getId()).get(0);
        BusinessException ex = assertThrows(BusinessException.class, () ->
                taskService.reassignTask(completedTask.getId(), adminUser.getId(), adminUser.getId(), "test"));
        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.caseNotActive", ex.getMessageKey(),
                "COMPLETED案件のタスク担当変更は400 caseNotActiveで拒否されること");
    }

    @Test
    @DisplayName("M-6: correctCompletedTask - 完了済みタスクへの訂正記録と認可スコープ検証 (LC-P1-15, LC-P1-18)")
    void testCorrectCompletedTask() {
        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("RESIGNATION")
                .templateId(resignationTemplate.getId())
                .anchorDate(LocalDate.now())
                .title("訂正テスト退社")
                .build());

        List<LifecycleTask> tasks = taskMapper.selectByCaseId(caseDto.getId());
        LifecycleTask task = tasks.get(0);
        taskService.completeTask(task.getId(), adminUser.getId(), null);

        // 1. 管理者による完了済みタスクへの訂正記録が成功すること
        assertDoesNotThrow(() ->
                taskService.correctCompletedTask(task.getId(), adminUser.getId(), "提出書類の誤記訂正：誓約書の日付を修正"));

        // 2. 未完了タスクへの訂正は拒否されること
        LifecycleTask pendingTask = taskMapper.selectByCaseId(caseDto.getId()).stream()
                .filter(t -> !"COMPLETED".equals(t.getStatus()) && !"WAIVED".equals(t.getStatus()))
                .findFirst()
                .orElse(null);
        assertNotNull(pendingTask, "未完了のタスクが存在すること");
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                taskService.correctCompletedTask(pendingTask.getId(), adminUser.getId(), "未完了タスクへの訂正試行"));
        assertEquals(400, ex1.getCode());
        assertEquals("error.lifecycle.taskNotCompleted", ex1.getMessageKey());

        // 3. HR担当タスクに対し、無権限の営業ユーザーが訂正しようとすると 404 (非公開) または 403 (権限なし) で拒否されること
        SysUser otherSales = SysUser.builder()
                .username("other_sales")
                .password("pass")
                .realName("無関係営業")
                .role("営業")
                .status(1)
                .build();
        sysUserMapper.insert(otherSales);
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                taskService.correctCompletedTask(task.getId(), otherSales.getId(), "無権限営業による訂正試行"));
        assertTrue(ex2.getCode() == 404 || ex2.getCode() == 403, "無権限ユーザーの訂正操作は404または403で拒否されること");

        // 4. CANCELLED案件のタスクへの訂正は 400 で拒否されること
        caseService.cancelCase(caseDto.getId(), adminUser.getId(), "案件中止");
        BusinessException ex3 = assertThrows(BusinessException.class, () ->
                taskService.correctCompletedTask(task.getId(), adminUser.getId(), "中止案件への訂正試行"));
        assertEquals(400, ex3.getCode());
        assertEquals("error.lifecycle.caseCancelled", ex3.getMessageKey());
    }

    @Test
    @DisplayName("M-7: isTaskVisibleToUser & assertCanEditTask - 実起票データによる営業ロールのHR機密タスクマスクと操作遮断検証 (LC-P1-14)")
    void testSalesRoleTaskMaskingAndEditBlocking() {
        // 実起票用テンプレート: PRIMARY_SALES(内部), ROLE:HR(内部), ENGINEER_SELF(公開)
        LifecycleTemplateDto salesMaskTpl = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("RESIGNATION")
                .name("営業マスク検証フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_SALES_HANDOVER")
                                .taskName("営業引継ぎ確認")
                                .assigneeRule("PRIMARY_SALES")
                                .isEngineerVisible(0) // 内部
                                .sortOrder(1)
                                .isMandatory(1)
                                .isBlocking(0)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_HR_INTERNAL")
                                .taskName("HR機密退職面談記録")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .isEngineerVisible(0) // 内部HR
                                .sortOrder(2)
                                .isMandatory(1)
                                .isBlocking(0)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("RESIGN_ENG_SURVEY")
                                .taskName("要員アンケート提出")
                                .assigneeRule("ENGINEER_SELF")
                                .isEngineerVisible(1) // 公開
                                .sortOrder(3)
                                .isMandatory(1)
                                .isBlocking(0)
                                .build()
                ))
                .build(), adminUser.getId());

        // 新規要員で案件起票 (主担当営業: salesUser)
        Engineer testEng = Engineer.builder().fullName("営業マスク検証要員").status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(testEng);
        EngineerSales es = EngineerSales.builder()
                .engineerId(testEng.getId())
                .salesUserId(salesUser.getId())
                .primaryFlag(1)
                .assignedAt(LocalDate.now().minusMonths(1))
                .build();
        engineerSalesMapper.insert(es);

        // 要員本人アカウント連携 (ENGINEER_SELF解決用)
        SysUser testEngUser = SysUser.builder()
                .username("eng_mask_user")
                .password("pass")
                .realName("マスク要員")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(testEngUser);

        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(testEng.getId());
        link.setSysUserId(testEngUser.getId());
        engineerAccountLinkMapper.insert(link);

        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(testEng.getId())
                .lifecycleType("RESIGNATION")
                .templateId(salesMaskTpl.getId())
                .anchorDate(LocalDate.now())
                .title("営業マスク検証案件")
                .build());

        List<LifecycleTask> tasks = taskMapper.selectByCaseId(caseDto.getId());
        LifecycleTask salesTask = tasks.stream().filter(t -> "RESIGN_SALES_HANDOVER".equals(t.getTaskCode())).findFirst().orElseThrow();
        LifecycleTask hrTask = tasks.stream().filter(t -> "RESIGN_HR_INTERNAL".equals(t.getTaskCode())).findFirst().orElseThrow();
        LifecycleTask engTask = tasks.stream().filter(t -> "RESIGN_ENG_SURVEY".equals(t.getTaskCode())).findFirst().orElseThrow();

        // 1. 本番解決データにおける assigneeRole の確認 (salesTaskは"営業", hrTaskは"HR")
        assertEquals("営業", salesTask.getAssigneeRole(), "PRIMARY_SALESルールのタスクは営業ロールとして解決されること");
        assertEquals("HR", hrTask.getAssigneeRole(), "ROLE:HRルールのタスクはHRロールとして解決されること");

        // 2. 閲覧権限 (isTaskVisibleToUser) の検証
        assertTrue(scopeService.isTaskVisibleToUser(salesUser, salesTask), "担当営業は営業関連の内部タスクを閲覧可能");
        assertFalse(scopeService.isTaskVisibleToUser(salesUser, hrTask), "担当営業はHR機密の内部タスクを閲覧不可");
        assertTrue(scopeService.isTaskVisibleToUser(salesUser, engTask), "担当営業は本人公開タスクを閲覧可能");
        assertTrue(scopeService.isTaskVisibleToUser(adminUser, hrTask), "管理者は全タスクを閲覧可能");

        // 3. 更新・操作権限 (assertCanEditTask) の検証
        LifecycleCase lcCase = caseMapper.selectById(caseDto.getId());
        // 担当営業は営業タスクを操作可能
        assertDoesNotThrow(() -> scopeService.assertCanEditTask(salesUser, salesTask, lcCase, testEng));
        // 担当営業がHR機密タスクを操作（complete/reassign等）しようとすると 404 で遮断されること
        BusinessException ex = assertThrows(BusinessException.class, () ->
                scopeService.assertCanEditTask(salesUser, hrTask, lcCase, testEng));
        assertEquals(404, ex.getCode(), "非公開HRタスクへの操作は存在を推測させない 404 で拒否されること");
        assertEquals("error.lifecycle.taskNotFound", ex.getMessageKey());
    }

    @Test
    @DisplayName("M-8: LifecycleEventMapper - イベント台帳のイミュータブル性検証 (LC-P1-15)")
    void testLifecycleEventMapperImmutability() {
        // LifecycleEventMapper の更新・削除メソッドはすべて UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class, () -> eventMapper.deleteById(1L));
        assertThrows(UnsupportedOperationException.class, () -> eventMapper.deleteBatchIds(List.of(1L, 2L)));
        assertThrows(UnsupportedOperationException.class, () -> eventMapper.deleteByMap(java.util.Map.of("id", 1L)));
        assertThrows(UnsupportedOperationException.class, () -> eventMapper.updateById(new LifecycleEvent()));
    }
}
