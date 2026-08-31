package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.Engineer;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
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
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class LifecycleApiControllerTest {

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
    private UserOrganizationMapper userOrganizationMapper;

    @Autowired
    private com.ses.mapper.ApprovalRequestMapper approvalRequestMapper;

    private SysUser adminUser;
    private Engineer engineer;
    private OrganizationUnit org;
    private LifecycleTemplateDto template;

    @BeforeEach
    void setUp() {
        adminUser = SysUser.builder()
                .username("admin_api_test")
                .password("pass")
                .realName("管理者テスト")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(adminUser);

        SysUser hrUser = SysUser.builder()
                .username("hr_api_test")
                .password("pass")
                .realName("人事テスト")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hrUser);

        org = OrganizationUnit.builder()
                .code("ORG-API-01")
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
                .fullName("要員APIテスト")
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineer.setOrganizationId(org.getId());
        engineerMapper.insert(engineer);

        template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("JOIN")
                .name("API入社フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("API_TASK_1")
                                .taskName("PC手配")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(1)
                                .isMandatory(1)
                                .isBlocking(1)
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("API_TASK_2")
                                .taskName("アカウント作成")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("管理者")
                                .sortOrder(2)
                                .isMandatory(1)
                                .isBlocking(0)
                                .predecessorTaskCodes(List.of("API_TASK_1"))
                                .build()
                ))
                .build(), adminUser.getId());
    }

    @Test
    @DisplayName("A1-1: ページコントローラー (/lifecycle, /lifecycle/templates, /lifecycle/{id}) が 200 OK を返すこと")
    @WithMockUser(username = "admin_api_test", roles = {"管理者"})
    void testPageControllers() throws Exception {
        mockMvc.perform(get("/lifecycle"))
                .andExpect(status().isOk())
                .andExpect(view().name("lifecycle/list"));

        mockMvc.perform(get("/lifecycle/templates"))
                .andExpect(status().isOk())
                .andExpect(view().name("lifecycle/templates"));

        var caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("JOIN")
                .templateId(template.getId())
                .anchorDate(LocalDate.now())
                .build());

        mockMvc.perform(get("/lifecycle/" + caseDto.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("lifecycle/detail"))
                .andExpect(model().attribute("caseId", caseDto.getId()));
    }

    @Test
    @DisplayName("A1-2: 案件起票・一覧・詳細取得 API 正常系")
    @WithMockUser(username = "admin_api_test", roles = {"管理者"})
    void testCaseCrudAndLifecycleFlow() throws Exception {
        // 1. 起票
        CreateLifecycleCaseCommand createCmd = CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("JOIN")
                .templateId(template.getId())
                .anchorDate(LocalDate.now())
                .title("2026年度新入社員オンボーディング")
                .remarks("特急手配要")
                .build();

        String createRes = mockMvc.perform(post("/api/lifecycle/cases")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.caseNo").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.tasks", hasSize(2)))
                .andReturn().getResponse().getContentAsString();

        Long caseId = objectMapper.readTree(createRes).get("data").get("id").asLong();
        Long task1Id = objectMapper.readTree(createRes).get("data").get("tasks").get(0).get("id").asLong();
        Long task2Id = objectMapper.readTree(createRes).get("data").get("tasks").get(1).get("id").asLong();

        // 2. 一覧取得
        mockMvc.perform(get("/api/lifecycle/cases")
                        .param("lifecycleType", "JOIN")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))));

        // 3. 詳細取得
        mockMvc.perform(get("/api/lifecycle/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(caseId))
                .andExpect(jsonPath("$.data.engineerName").value("要員APIテスト"));

        // 4. 先行タスク1の完了
        CompleteLifecycleTaskCommand compCmd = CompleteLifecycleTaskCommand.builder()
                .completionComment("PC配備完了")
                .build();
        mockMvc.perform(post("/api/lifecycle/tasks/" + task1Id + "/complete")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(compCmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 承認なき直接免除は 400 で拒否されること (LC-P0-01)
        mockMvc.perform(post("/api/lifecycle/tasks/" + task2Id + "/waive")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "既存アカウント流用のため免除"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 正当な承認完了（ApprovalRequest APPROVED）を作成
        com.ses.entity.ApprovalRequest waiverReq = com.ses.entity.ApprovalRequest.builder()
                .requestNo("AR-LC-API-001")
                .requestType("LIFECYCLE_EXCEPTION")
                .targetType("LIFECYCLE_TASK")
                .targetId(task2Id)
                .targetVersion(0L)
                .applicantId(adminUser.getId())
                .routeSnapshotJson("[]")
                .status("APPROVED")
                .payloadJson("{\"reason\":\"既存アカウント流用\",\"riskOwner\":\"管理者\",\"remedyDeadline\":\"" + LocalDate.now().plusMonths(1) + "\"}")
                .build();
        approvalRequestMapper.insert(waiverReq);

        // 承認ID付きで免除実行 -> 成功
        mockMvc.perform(post("/api/lifecycle/tasks/" + task2Id + "/waive")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "approvalRequestId", waiverReq.getId(),
                                "reason", "既存アカウント流用のため免除"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 6. 案件完了確定
        mockMvc.perform(post("/api/lifecycle/cases/" + caseId + "/complete")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 完了状態確認
        mockMvc.perform(get("/api/lifecycle/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("A1-3: 案件が空の場合も一覧APIが空配列を返すこと")
    @WithMockUser(username = "admin_api_test", roles = {"管理者"})
    void 案件が空の場合も一覧APIが空配列を返す() throws Exception {
        mockMvc.perform(get("/api/lifecycle/cases").param("engineerId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/lifecycle/cases").param("status", "存在しない状態"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/lifecycle/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("A1-3: 案件の保留・再開・中止 API テスト")
    @WithMockUser(username = "admin_api_test", roles = {"管理者"})
    void testHoldResumeCancelFlow() throws Exception {
        var caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("JOIN")
                .templateId(template.getId())
                .anchorDate(LocalDate.now())
                .build());

        // 保留
        mockMvc.perform(post("/api/lifecycle/cases/" + caseDto.getId() + "/hold")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "入社日延期"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 再開
        mockMvc.perform(post("/api/lifecycle/cases/" + caseDto.getId() + "/resume")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 中止
        mockMvc.perform(post("/api/lifecycle/cases/" + caseDto.getId() + "/cancel")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "内定辞退"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/lifecycle/cases/" + caseDto.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("A1-4: テンプレート CRUD & ステータス切替 API テスト")
    @WithMockUser(username = "admin_api_test", roles = {"管理者"})
    void testTemplateCrudApi() throws Exception {
        // 一覧取得
        mockMvc.perform(get("/api/lifecycle/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))));

        // 詳細取得
        mockMvc.perform(get("/api/lifecycle/templates/" + template.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("API入社フロー"));

        // ステータス切替
        mockMvc.perform(post("/api/lifecycle/templates/" + template.getId() + "/toggle-status")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/lifecycle/templates/" + template.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }
}
