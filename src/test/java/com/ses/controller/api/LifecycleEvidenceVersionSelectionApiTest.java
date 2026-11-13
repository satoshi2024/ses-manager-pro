package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LifecycleEvidenceLink;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleEvidenceLinkMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.lifecycle.LifecycleCaseService;
import com.ses.service.lifecycle.LifecycleTemplateService;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NF01 N1-3 / Q-9: 証跡は画面で選んだCLEAN版を固定し、版を指定しない（versionless）証跡は400で拒否する。
 * 版一覧APIは完了操作と同じtenant・権限・本人scopeで判定し、元ファイル名等を返さない。
 * あわせてN1-2のテンプレートAPIによるDUAL_CONFIRMATION拒否をHTTP経路で確認する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class LifecycleEvidenceVersionSelectionApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LifecycleTemplateService templateService;
    @Autowired private LifecycleCaseService caseService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private OrganizationUnitMapper organizationUnitMapper;
    @Autowired private UserOrganizationMapper userOrganizationMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private DocumentVersionMapper documentVersionMapper;
    @Autowired private LifecycleTaskMapper taskMapper;
    @Autowired private LifecycleEvidenceLinkMapper evidenceLinkMapper;
    @Autowired private LifecycleTemplateMapper templateMapper;

    private SysUser adminUser;
    private SysUser engineerUser1;
    private SysUser engineerUser2;
    private Long visibleDocTaskId;
    private Long internalDocTaskId;
    private Document document;
    private DocumentVersion cleanV1;
    private DocumentVersion pendingV2;
    private DocumentVersion cleanV3;

    @BeforeEach
    void setUp() {
        long suffix = System.nanoTime();
        adminUser = user("lc_evidence_admin_" + suffix, "管理者");
        SysUser hrUser = user("lc_evidence_hr_" + suffix, "HR");
        engineerUser1 = user("lc_evidence_eng1_" + suffix, "要員");
        engineerUser2 = user("lc_evidence_eng2_" + suffix, "要員");

        OrganizationUnit org = OrganizationUnit.builder()
                .tenantId(1L)
                .legalEntityId(1L)
                .code("ORG-EVV-" + suffix)
                .name("証跡版選択部-" + suffix)
                .type("DEPARTMENT")
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        organizationUnitMapper.insert(org);
        for (SysUser member : List.of(adminUser, hrUser, engineerUser1, engineerUser2)) {
            userOrganizationMapper.insert(UserOrganization.builder()
                    .tenantId("default")
                    .userId(member.getId())
                    .organizationId(org.getId())
                    .primaryFlag(1)
                    .validFrom(LocalDate.now().minusYears(1))
                    .version(0)
                    .build());
        }

        Engineer engineer1 = engineer("証跡版選択要員1-" + suffix, org.getId());
        Engineer engineer2 = engineer("証跡版選択要員2-" + suffix, org.getId());
        link(engineer1, engineerUser1);
        link(engineer2, engineerUser2);

        LifecycleTemplateDto template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("JOIN")
                .name("証跡版選択フロー-" + suffix)
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("EVV_SELF_DOC")
                                .taskName("本人提出書類")
                                .assigneeRule("ENGINEER_SELF")
                                .sortOrder(1)
                                .isMandatory(1)
                                .isBlocking(1)
                                .isEngineerVisible(1)
                                .evidenceType("DOCUMENT_LINK")
                                .build(),
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("EVV_INTERNAL_DOC")
                                .taskName("社内保管書類")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(2)
                                .isMandatory(1)
                                .isBlocking(1)
                                .isEngineerVisible(0)
                                .evidenceType("DOCUMENT_LINK")
                                .build()))
                .build(), adminUser.getId());

        var caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer1.getId())
                .lifecycleType("JOIN")
                .templateId(template.getId())
                .anchorDate(LocalDate.now())
                .title("証跡版選択案件")
                .build());
        visibleDocTaskId = caseDto.getTasks().stream()
                .filter(t -> "EVV_SELF_DOC".equals(t.getTaskCode())).findFirst().orElseThrow().getId();
        internalDocTaskId = caseDto.getTasks().stream()
                .filter(t -> "EVV_INTERNAL_DOC".equals(t.getTaskCode())).findFirst().orElseThrow().getId();

        document = document("default", "本人提出書類-" + suffix);
        cleanV1 = version("default", document.getId(), 1, "CLEAN");
        pendingV2 = version("default", document.getId(), 2, "PENDING");
        cleanV3 = version("default", document.getId(), 3, "CLEAN");
    }

    @Test
    @DisplayName("要員本人は自分の公開タスクについて文書のCLEAN版だけを版番号の降順で取得できる")
    void 要員本人は自分の公開タスクのCLEAN版だけを降順で取得できる() throws Exception {
        mockMvc.perform(get("/api/my/lifecycle/tasks/" + visibleDocTaskId + "/evidence-versions")
                        .param("documentId", String.valueOf(document.getId()))
                        .with(asRole(engineerUser1, "要員")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(cleanV3.getId()))
                .andExpect(jsonPath("$.data[0].versionNo").value(3))
                .andExpect(jsonPath("$.data[1].id").value(cleanV1.getId()))
                .andExpect(jsonPath("$.data[0].originalName").doesNotExist())
                .andExpect(jsonPath("$.data[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].sha256").doesNotExist());
    }

    @Test
    @DisplayName("他要員のタスク・社内専用タスクの版一覧は存在を推測させない404で拒否する")
    void 他要員と社内専用タスクの版一覧は404で拒否する() throws Exception {
        mockMvc.perform(get("/api/my/lifecycle/tasks/" + visibleDocTaskId + "/evidence-versions")
                        .param("documentId", String.valueOf(document.getId()))
                        .with(asRole(engineerUser2, "要員")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(get("/api/my/lifecycle/tasks/" + internalDocTaskId + "/evidence-versions")
                        .param("documentId", String.valueOf(document.getId()))
                        .with(asRole(engineerUser1, "要員")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("他tenantの文書は存在しない文書と同じ404で拒否し、版を一覧に出さない")
    void 他tenantの文書は存在しない文書と同じ404で拒否する() throws Exception {
        Document foreign = document("tenant-evv-foreign", "他tenant書類");
        version("tenant-evv-foreign", foreign.getId(), 1, "CLEAN");

        mockMvc.perform(get("/api/lifecycle/tasks/" + internalDocTaskId + "/evidence-versions")
                        .param("documentId", String.valueOf(foreign.getId()))
                        .with(asRole(adminUser, "管理者")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/lifecycle/tasks/" + internalDocTaskId + "/evidence-versions")
                        .param("documentId", "987654321")
                        .with(asRole(adminUser, "管理者")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        // 同tenantの文書なら管理者は社内専用タスクの版一覧を取得できる
        mockMvc.perform(get("/api/lifecycle/tasks/" + internalDocTaskId + "/evidence-versions")
                        .param("documentId", String.valueOf(document.getId()))
                        .with(asRole(adminUser, "管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("版を指定しない完了報告は400で拒否され、タスクも証跡linkも変わらない")
    void 版を指定しない完了報告は400で拒否されタスクも証跡も変わらない() throws Exception {
        mockMvc.perform(post("/api/my/lifecycle/tasks/" + visibleDocTaskId + "/complete")
                        .with(asRole(engineerUser1, "要員"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CompleteLifecycleTaskCommand.builder()
                                .documentId(document.getId())
                                .completionComment("版を選ばない提出")
                                .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        assertEquals("IN_PROGRESS", taskMapper.selectById(visibleDocTaskId).getStatus());
        assertTrue(evidenceLinkMapper.selectByTaskId(visibleDocTaskId).isEmpty());
    }

    @Test
    @DisplayName("CLEANでない版は400で拒否し、画面で選んだCLEAN版なら証跡として固定して完了できる")
    void CLEANでない版は拒否し選んだCLEAN版で完了できる() throws Exception {
        mockMvc.perform(post("/api/my/lifecycle/tasks/" + visibleDocTaskId + "/complete")
                        .with(asRole(engineerUser1, "要員"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CompleteLifecycleTaskCommand.builder()
                                .documentId(document.getId())
                                .documentVersionId(pendingV2.getId())
                                .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        assertEquals("IN_PROGRESS", taskMapper.selectById(visibleDocTaskId).getStatus());

        mockMvc.perform(post("/api/my/lifecycle/tasks/" + visibleDocTaskId + "/complete")
                        .with(asRole(engineerUser1, "要員"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CompleteLifecycleTaskCommand.builder()
                                .documentId(document.getId())
                                .documentVersionId(cleanV3.getId())
                                .completionComment("第3版を提出")
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals("COMPLETED", taskMapper.selectById(visibleDocTaskId).getStatus());
        List<LifecycleEvidenceLink> links = evidenceLinkMapper.selectByTaskId(visibleDocTaskId);
        assertEquals(1, links.size());
        assertEquals(cleanV3.getId(), links.get(0).getDocumentVersionId());
    }

    @Test
    @DisplayName("テンプレートAPIはCONFIRM公開前のDUAL_CONFIRMATIONを含む作成を400で拒否しテンプレートを残さない")
    void テンプレートAPIはDUAL_CONFIRMATIONを含む作成を400で拒否する() throws Exception {
        String name = "二者確認拒否API-" + System.nanoTime();
        LifecycleTemplateDto dual = LifecycleTemplateDto.builder()
                .templateType("JOIN")
                .name(name)
                .validFrom(LocalDate.now())
                .tasks(List.of(LifecycleTemplateTaskDto.builder()
                        .taskCode("EVV_DUAL")
                        .taskName("二者確認")
                        .assigneeRule("ROLE")
                        .assigneeRuleValue("管理者")
                        .evidenceType("DUAL_CONFIRMATION")
                        .build()))
                .build();

        mockMvc.perform(post("/api/lifecycle/templates")
                        .with(asRole(adminUser, "管理者"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dual)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        assertEquals(0L, templateMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.LifecycleTemplate>()
                        .eq(com.ses.entity.LifecycleTemplate::getName, name)));
    }

    private RequestPostProcessor asRole(SysUser user, String role) {
        return authentication(TenantTestSecurity.authentication(user.getId(), user.getUsername(), "default", role));
    }

    private SysUser user(String username, String role) {
        SysUser user = SysUser.builder()
                .tenantId("default")
                .username(username)
                .password("pass")
                .realName(username)
                .role(role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user;
    }

    private Engineer engineer(String name, Long organizationId) {
        Engineer engineer = Engineer.builder()
                .tenantId("default")
                .legalEntityId(1L)
                .fullName(name)
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineer.setOrganizationId(organizationId);
        engineerMapper.insert(engineer);
        return engineer;
    }

    private void link(Engineer engineer, SysUser user) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId("default");
        link.setEngineerId(engineer.getId());
        link.setSysUserId(user.getId());
        engineerAccountLinkMapper.insert(link);
    }

    private Document document(String tenantId, String title) {
        Document document = new Document();
        document.setTenantId(tenantId);
        document.setDocumentType("LIFECYCLE_EVIDENCE");
        document.setTitle(title);
        document.setDirection("INTERNAL");
        document.setStatus("CONFIRMED");
        documentMapper.insert(document);
        return document;
    }

    private DocumentVersion version(String tenantId, Long documentId, int versionNo, String scanStatus) {
        DocumentVersion version = new DocumentVersion();
        version.setTenantId(tenantId);
        version.setDocumentId(documentId);
        version.setVersionNo(versionNo);
        version.setStorageKey("lifecycle-evv/" + documentId + "-" + versionNo + "-" + System.nanoTime());
        version.setOriginalName("evidence-v" + versionNo + ".pdf");
        version.setContentType("application/pdf");
        version.setSizeBytes(1L);
        version.setSha256("c".repeat(64));
        version.setSourceType("LIFECYCLE_TEST");
        version.setBusinessKey("lifecycle-evv-" + documentId + "-" + versionNo + "-" + System.nanoTime());
        version.setVersionDiscriminator("v" + versionNo);
        version.setScanStatus(scanStatus);
        documentVersionMapper.insert(version);
        return version;
    }
}
