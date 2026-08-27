package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.entity.*;
import com.ses.mapper.*;
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
    @DisplayName("M-6: correctCompletedTask - 完了済みタスクへの訂正記録 (LC-P1-15)")
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

        // 完了済みタスクへの訂正記録が成功すること
        assertDoesNotThrow(() ->
                taskService.correctCompletedTask(task.getId(), adminUser.getId(), "提出書類の誤記訂正：誓約書の日付を修正"));

        // 未完了タスクへの訂正は拒否されること
        LifecycleTask pendingTask = taskMapper.selectByCaseId(caseDto.getId()).stream()
                .filter(t -> !"COMPLETED".equals(t.getStatus()) && !"WAIVED".equals(t.getStatus()))
                .findFirst()
                .orElse(null);
        assertNotNull(pendingTask, "未完了のタスクが存在すること");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                taskService.correctCompletedTask(pendingTask.getId(), adminUser.getId(), "未完了タスクへの訂正試行"));
        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.taskNotCompleted", ex.getMessageKey());
    }

    @Test
    @DisplayName("M-7: isTaskVisibleToUser - 営業ロールのHR機密タスクマスク検証 (LC-P1-14)")
    void testSalesRoleTaskMasking() {
        // 内部タスク (is_engineer_visible=0, assignee_role=HR) — 営業に非公開
        LifecycleTask hrTask = LifecycleTask.builder()
                .caseId(1L)
                .taskCode("INTERNAL_HR_TASK")
                .taskName("HR機密タスク")
                .dueDate(LocalDate.now())
                .assigneeRole("HR")
                .isEngineerVisible(0)
                .status("PENDING")
                .version(0)
                .build();

        // 営業関連内部タスク (is_engineer_visible=0, assignee_role=PRIMARY_SALES) — 営業に公開
        LifecycleTask salesTask = LifecycleTask.builder()
                .caseId(1L)
                .taskCode("SALES_TASK")
                .taskName("営業関連タスク")
                .dueDate(LocalDate.now())
                .assigneeRole("PRIMARY_SALES")
                .isEngineerVisible(0)
                .status("PENDING")
                .version(0)
                .build();

        // 公開タスク (is_engineer_visible=1)
        LifecycleTask publicTask = LifecycleTask.builder()
                .caseId(1L)
                .taskCode("PUBLIC_TASK")
                .taskName("公開タスク")
                .dueDate(LocalDate.now())
                .assigneeRole("HR")
                .isEngineerVisible(1)
                .status("PENDING")
                .version(0)
                .build();

        assertFalse(scopeService.isTaskVisibleToUser(salesUser, hrTask),
                "営業ロールはHR機密の内部タスク（is_engineer_visible=0, role=HR）を閲覧不可");
        assertTrue(scopeService.isTaskVisibleToUser(salesUser, salesTask),
                "営業ロールはPRIMARY_SALES担当の内部タスクを閲覧可能");
        assertTrue(scopeService.isTaskVisibleToUser(salesUser, publicTask),
                "営業ロールは公開タスク（is_engineer_visible=1）を閲覧可能");
        assertTrue(scopeService.isTaskVisibleToUser(adminUser, hrTask),
                "管理者は全タスクを閲覧可能");
    }
}

