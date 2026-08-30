package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.lifecycle.LifecycleCaseService;
import com.ses.service.lifecycle.LifecycleTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class MyLifecycleApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LifecycleTemplateService templateService;

    @Autowired
    private LifecycleCaseService caseService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    private SysUser adminUser;
    private SysUser engineerUser1;
    private SysUser engineerUser2;
    private Engineer engineer1;
    private Engineer engineer2;
    private LifecycleTemplateDto template;
    private Long case1Id;
    private Long taskVisibleId;
    private Long taskInternalId;

    @BeforeEach
    void setUp() {
        adminUser = SysUser.builder()
                .username("admin_my_test")
                .password("pass")
                .realName("管理者テスト")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(adminUser);

        SysUser hrUser = SysUser.builder()
                .username("hr_my_test")
                .password("pass")
                .realName("人事テスト")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hrUser);

        engineerUser1 = SysUser.builder()
                .username("eng_user_01")
                .password("pass")
                .realName("要員テスト1")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(engineerUser1);

        engineerUser2 = SysUser.builder()
                .username("eng_user_02")
                .password("pass")
                .realName("要員テスト2")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(engineerUser2);

        OrganizationUnit org = OrganizationUnit.builder()
                .code("ORG-MY-01")
                .name("開発部")
                .type("DEPARTMENT")
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        organizationUnitMapper.insert(org);

        engineer1 = Engineer.builder()
                .fullName("要員テスト1")
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineer1.setOrganizationId(org.getId());
        engineerMapper.insert(engineer1);

        engineer2 = Engineer.builder()
                .fullName("要員テスト2")
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineer2.setOrganizationId(org.getId());
        engineerMapper.insert(engineer2);

        // 共有H2では別contextが採番を再利用するため、前回fixtureのリンクを先に除去する。
        engineerAccountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getEngineerId, engineer1.getId()));
        engineerAccountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getEngineerId, engineer2.getId()));
        engineerAccountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, engineerUser1.getId()));
        engineerAccountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, engineerUser2.getId()));

        EngineerAccountLink link1 = new EngineerAccountLink();
        link1.setEngineerId(engineer1.getId());
        link1.setSysUserId(engineerUser1.getId());
        engineerAccountLinkMapper.insert(link1);

        EngineerAccountLink link2 = new EngineerAccountLink();
        link2.setEngineerId(engineer2.getId());
        link2.setSysUserId(engineerUser2.getId());
        engineerAccountLinkMapper.insert(link2);

        // テンプレート作成（公開タスク1件、社内専用タスク1件）
        template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("JOIN")
                .name("本人提出フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("MY_SUBMIT_INFO")
                                .taskName("基礎情報・緊急連絡先提出")
                                .assigneeRule("ENGINEER_SELF")
                                .sortOrder(1)
                                .isMandatory(1)
                                .isBlocking(1)
                                .isEngineerVisible(1)
                                .evidenceType("SELF_DECLARATION")
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("INTERNAL_SECURITY_CHECK")
                                .taskName("社内セキュリティ・反社チェック")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(2)
                                .isMandatory(1)
                                .isBlocking(1)
                                .isEngineerVisible(0) // 非公開社内タスク
                                .evidenceType("NONE")
                                .build()
                ))
                .build(), adminUser.getId());

        var caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer1.getId())
                .lifecycleType("JOIN")
                .templateId(template.getId())
                .anchorDate(LocalDate.now())
                .title("要員1入社手続き")
                .build());

        case1Id = caseDto.getId();
        taskVisibleId = caseDto.getTasks().stream().filter(t -> "MY_SUBMIT_INFO".equals(t.getTaskCode())).findFirst().get().getId();
        taskInternalId = caseDto.getTasks().stream().filter(t -> "INTERNAL_SECURITY_CHECK".equals(t.getTaskCode())).findFirst().get().getId();
    }

    @Test
    @DisplayName("A2-1: 要員本人画面 (/my/lifecycle, /my/lifecycle/{id}) のアクセス検証")
    @WithMockUser(username = "eng_user_01", roles = {"要員"})
    void testMyLifecyclePages() throws Exception {
        mockMvc.perform(get("/my/lifecycle"))
                .andExpect(status().isOk())
                .andExpect(view().name("my/lifecycle"));

        mockMvc.perform(get("/my/lifecycle/" + case1Id))
                .andExpect(status().isOk())
                .andExpect(view().name("my/lifecycle-detail"))
                .andExpect(model().attribute("caseId", case1Id));
    }

    @Test
    @DisplayName("A2-2: 要員本人APIで自案件が取得でき、社内専用タスクとスナップショットが除外されていること")
    @WithMockUser(username = "eng_user_01", roles = {"要員"})
    void testListMyCasesAndConcealedInternalTasks() throws Exception {
        mockMvc.perform(get("/api/my/lifecycle/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(case1Id))
                .andExpect(jsonPath("$.data[0].title").value("要員1入社手続き"));

        mockMvc.perform(get("/api/my/lifecycle/cases/" + case1Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 公開タスク1件のみが含まれ、社内専用タスクは0件 (除外)
                .andExpect(jsonPath("$.data.tasks", hasSize(1)))
                .andExpect(jsonPath("$.data.tasks[0].taskCode").value("MY_SUBMIT_INFO"))
                .andExpect(jsonPath("$.data.totalTasks").value(1))
                .andExpect(jsonPath("$.data.engineerSnapshotJson").doesNotExist());
    }

    @Test
    @DisplayName("A2-3: 要員本人による公開タスクの完了報告が成功すること")
    @WithMockUser(username = "eng_user_01", roles = {"要員"})
    void testCompleteMyVisibleTask() throws Exception {
        CompleteLifecycleTaskCommand cmd = CompleteLifecycleTaskCommand.builder()
                .completionComment("緊急連絡先と通勤経路を登録しました")
                .build();

        mockMvc.perform(post("/api/my/lifecycle/tasks/" + taskVisibleId + "/complete")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("A2-4: 要員本人が非公開社内タスクへ完了APIを直接呼んだ場合 404 で拒否されること (推測防止)")
    @WithMockUser(username = "eng_user_01", roles = {"要員"})
    void testRejectInternalTaskCompletionByEngineer() throws Exception {
        CompleteLifecycleTaskCommand cmd = CompleteLifecycleTaskCommand.builder()
                .completionComment("社内タスクを改ざん完了試行")
                .build();

        mockMvc.perform(post("/api/my/lifecycle/tasks/" + taskInternalId + "/complete")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("A2-5: 別要員 (eng_user_02) が他要員の案件詳細やタスクにアクセスした場合 404 で拒否されること")
    @WithMockUser(username = "eng_user_02", roles = {"要員"})
    void testRejectAccessToOtherEngineerCase() throws Exception {
        mockMvc.perform(get("/api/my/lifecycle/cases/" + case1Id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        CompleteLifecycleTaskCommand cmd = CompleteLifecycleTaskCommand.builder()
                .completionComment("他要員タスク完了試行")
                .build();

        mockMvc.perform(post("/api/my/lifecycle/tasks/" + taskVisibleId + "/complete")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
