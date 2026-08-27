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
    private EngineerSalesMapper engineerSalesMapper;

    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private LifecycleCaseMapper caseMapper;

    @Autowired
    private LifecycleTaskMapper taskMapper;

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
    }

    @Test
    @DisplayName("M-3: 退社ゲート評価単体での8項目チェック検証")
    void testResignationGateEvaluationChecklist() {
        LifecycleCase lcCase = LifecycleCase.builder()
                .caseNo("LC-DRILL-001")
                .lifecycleType("RESIGNATION")
                .engineerId(engineer.getId())
                .anchorDate(LocalDate.now())
                .status("ACTIVE")
                .title("退社テスト")
                .build();
        lcCase.setId(999L);

        ResignationGateResultDto result = resignationGateChecker.evaluate(lcCase, engineer);
        assertNotNull(result);
        assertNotNull(result.getItems());
        assertEquals(8, result.getItems().size(), "8項目のゲート項目が存在すること");
    }
}
