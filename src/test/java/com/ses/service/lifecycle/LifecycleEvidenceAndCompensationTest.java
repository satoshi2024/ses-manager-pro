package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleEvidenceAndCompensationTest {

    @Autowired
    private LifecycleTemplateService templateService;

    @Autowired
    private LifecycleCaseService caseService;

    @Autowired
    private LifecycleTaskService taskService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private LifecycleTaskMapper taskMapper;

    @Autowired
    private LifecycleCaseMapper caseMapper;

    @Autowired
    private LifecycleEvidenceLinkMapper evidenceLinkMapper;

    @Autowired
    private LifecycleEventMapper eventMapper;

    private SysUser adminUser;
    private Engineer engineer;
    private Document validDocument;
    private LifecycleTemplateDto template;
    private Long caseId;
    private Long taskId;

    @BeforeEach
    void setUp() {
        adminUser = SysUser.builder()
                .username("admin_b2_test")
                .password("pass")
                .realName("管理者B2")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(adminUser);

        SysUser hrUser = SysUser.builder()
                .username("hr_b2_test")
                .password("pass")
                .realName("人事B2")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hrUser);

        OrganizationUnit org = OrganizationUnit.builder()
                .code("ORG-B2-01")
                .name("開発部")
                .type("DEPARTMENT")
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusYears(1))
                .build();
        organizationUnitMapper.insert(org);

        engineer = Engineer.builder()
                .fullName("要員B2テスト")
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineer.setOrganizationId(org.getId());
        engineerMapper.insert(engineer);

        validDocument = new Document();
        validDocument.setTenantId("default");
        validDocument.setDocumentType("LIFECYCLE_EVIDENCE");
        validDocument.setTitle("雇用誓約書原本");
        validDocument.setDirection("INTERNAL");
        validDocument.setStatus("CONFIRMED");
        documentMapper.insert(validDocument);

        template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("JOIN")
                .name("証跡提出フロー")
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(
                        LifecycleTemplateTaskDto.builder()
                                .taskCode("PLEDGE_DOC_LINK")
                                .taskName("誓約書原本受領・台帳登録")
                                .assigneeRule("ROLE")
                                .assigneeRuleValue("HR")
                                .sortOrder(1)
                                .isMandatory(1)
                                .isBlocking(1)
                                .evidenceType("DOCUMENT_LINK")
                                .build()
                ))
                .build(), adminUser.getId());

        LifecycleCaseDto caseDto = caseService.createCase(adminUser.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("JOIN")
                .templateId(template.getId())
                .anchorDate(LocalDate.now())
                .title("B2テスト案件")
                .build());

        caseId = caseDto.getId();
        taskId = caseDto.getTasks().get(0).getId();
    }

    @Test
    @DisplayName("B2-1: 存在しない文書IDを指定して完了報告した場合 400 エラーで拒否されること")
    void testRejectNonExistentDocumentLink() {
        CompleteLifecycleTaskCommand cmd = CompleteLifecycleTaskCommand.builder()
                .documentId(9999999L) // 存在しないID
                .evidenceRemarks("存在しない文書リンク試行")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                taskService.completeTask(taskId, adminUser.getId(), cmd));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("B2-2: 有効な文書台帳IDを指定して完了報告した場合、証跡リンクが正しく永続化されること")
    void testSuccessDocumentLinkAndEvidenceRecord() {
        CompleteLifecycleTaskCommand cmd = CompleteLifecycleTaskCommand.builder()
                .documentId(validDocument.getId())
                .evidenceRemarks("原本確認完了")
                .completionComment("人事部にて原本受理")
                .build();

        assertDoesNotThrow(() -> taskService.completeTask(taskId, adminUser.getId(), cmd));

        LifecycleTask updatedTask = taskMapper.selectById(taskId);
        assertEquals("COMPLETED", updatedTask.getStatus());
        assertEquals("人事部にて原本受理", updatedTask.getCompletionComment());

        List<LifecycleEvidenceLink> links = evidenceLinkMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LifecycleEvidenceLink>()
                        .eq(LifecycleEvidenceLink::getTaskId, taskId)
        );
        assertEquals(1, links.size());
        assertEquals(validDocument.getId(), links.get(0).getDocumentId());
        assertEquals("原本確認完了", links.get(0).getRemarks());
    }

    @Test
    @DisplayName("B2-3: 案件中止（cancelCase）時の補償処理と後続操作禁止の検証")
    void testCancelCaseCompensationAndGuard() {
        // 案件を中止
        caseService.cancelCase(caseId, adminUser.getId(), "本人の家庭都合による入社辞退");

        LifecycleCase cancelledCase = caseMapper.selectById(caseId);
        assertEquals("CANCELLED", cancelledCase.getStatus());

        // 未完了タスクがCANCELLEDに補償遷移されていること (LC-P1-11)
        LifecycleTask cancelledTask = taskMapper.selectById(taskId);
        assertEquals("CANCELLED", cancelledTask.getStatus());
        assertTrue(cancelledTask.getCompletionComment().contains("案件中止に伴うキャンセル"));

        // 中止イベントが記録されていること
        List<LifecycleEvent> events = eventMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LifecycleEvent>()
                        .eq(LifecycleEvent::getCaseId, caseId)
        );
        assertTrue(events.stream().anyMatch(e -> "CASE_CANCELLED".equals(e.getEventType())));

        // 中止案件に対する再開・完了・タスク着手の試行は拒否されること
        assertThrows(BusinessException.class, () -> caseService.completeCase(caseId, adminUser.getId()));
        assertThrows(BusinessException.class, () -> caseService.resumeCase(caseId, adminUser.getId()));
        assertThrows(BusinessException.class, () -> taskService.startTask(taskId, adminUser.getId()));
    }
}
