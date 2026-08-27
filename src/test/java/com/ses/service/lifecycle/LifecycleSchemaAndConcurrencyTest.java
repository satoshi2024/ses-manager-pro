package com.ses.service.lifecycle;

import com.ses.entity.*;
import com.ses.mapper.*;
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

@SpringBootTest
@ActiveProfiles("test")
class LifecycleSchemaAndConcurrencyTest {

    @Autowired
    private LifecycleTemplateMapper templateMapper;

    @Autowired
    private LifecycleTemplateTaskMapper templateTaskMapper;

    @Autowired
    private LifecycleTemplateTaskDepMapper templateTaskDepMapper;

    @Autowired
    private LifecycleCaseMapper caseMapper;

    @Autowired
    private LifecycleTaskMapper taskMapper;

    @Autowired
    private LifecycleTaskDepMapper taskDepMapper;

    @Autowired
    private LifecycleEvidenceLinkMapper evidenceLinkMapper;

    @Autowired
    private LifecycleEventMapper eventMapper;

    @Test
    @Transactional
    @DisplayName("F1: テンプレートとタスク定義の作成・検索・版番号取得ができる")
    void testTemplateAndTaskDefinitions() {
        LocalDate today = LocalDate.now();
        LifecycleTemplate template = LifecycleTemplate.builder()
                .templateType("JOIN")
                .name("正社員標準入社フロー")
                .description("入社前の準備から入社初日・配属までのタスク群")
                .versionNo(1)
                .status("ACTIVE")
                .validFrom(today.minusDays(1))
                .validTo(null)
                .createdBy(1L)
                .build();
        templateMapper.insert(template);
        assertNotNull(template.getId());

        LifecycleTemplateTask task1 = LifecycleTemplateTask.builder()
                .templateId(template.getId())
                .taskCode("JOIN_DOC_SUBMIT")
                .taskName("入社書類提出")
                .description("雇用契約書・身元保証書・誓約書の提出")
                .relativeDueDays(-3)
                .assigneeRule("ENGINEER_SELF")
                .isMandatory(1)
                .isBlocking(1)
                .evidenceType("DOCUMENT_LINK")
                .isEngineerVisible(1)
                .targetEmploymentTypes("正社員,契約社員")
                .sortOrder(10)
                .build();
        templateTaskMapper.insert(task1);

        LifecycleTemplateTask task2 = LifecycleTemplateTask.builder()
                .templateId(template.getId())
                .taskCode("JOIN_ACC_ISSUE")
                .taskName("アカウント発行")
                .description("内部システム・メール・Slackアカウントの発行")
                .relativeDueDays(-1)
                .assigneeRule("ROLE")
                .assigneeRuleValue("HR")
                .isMandatory(1)
                .isBlocking(1)
                .evidenceType("SYSTEM_CHECK")
                .isEngineerVisible(0)
                .sortOrder(20)
                .build();
        templateTaskMapper.insert(task2);

        LifecycleTemplateTaskDep dep = LifecycleTemplateTaskDep.builder()
                .templateId(template.getId())
                .predecessorTaskCode("JOIN_DOC_SUBMIT")
                .successorTaskCode("JOIN_ACC_ISSUE")
                .build();
        templateTaskDepMapper.insert(dep);

        // findActiveByTypeAndDate 検証
        LifecycleTemplate active = templateMapper.findActiveByTypeAndDate("JOIN", today);
        assertNotNull(active);
        assertEquals(template.getId(), active.getId());

        List<LifecycleTemplateTask> tasks = templateTaskMapper.selectByTemplateId(template.getId());
        assertEquals(2, tasks.size());
        assertEquals("JOIN_DOC_SUBMIT", tasks.get(0).getTaskCode());

        List<LifecycleTemplateTaskDep> deps = templateTaskDepMapper.selectByTemplateId(template.getId());
        assertEquals(1, deps.size());
        assertEquals("JOIN_DOC_SUBMIT", deps.get(0).getPredecessorTaskCode());
    }

    @Test
    @Transactional
    @DisplayName("F1: 案件およびタスクの作成・更新と楽観ロック(version CAS)が正しく機能する")
    void testCaseAndTaskOptimisticLocking() {
        LocalDate today = LocalDate.now();
        LifecycleCase lcCase = LifecycleCase.builder()
                .caseNo("LC-202608-TEST01")
                .lifecycleType("RESIGNATION")
                .engineerId(101L)
                .templateId(1L)
                .templateVersion(1)
                .anchorDate(today.plusDays(14))
                .status("ACTIVE")
                .title("山田太郎 退社手続き")
                .applicantUserId(1L)
                .engineerSnapshotJson("{\"engineerId\":101,\"fullName\":\"山田太郎\",\"employmentType\":\"正社員\"}")
                .version(0)
                .build();
        caseMapper.insert(lcCase);
        assertNotNull(lcCase.getId());
        assertEquals(0, lcCase.getVersion());

        // 案件の通常更新 -> versionが1になる
        lcCase.setStatus("ON_HOLD");
        int updatedRows = caseMapper.updateById(lcCase);
        assertEquals(1, updatedRows);
        assertEquals(1, lcCase.getVersion());

        // 古いバージョンを指定して更新試行 -> 0件更新 (CAS検知)
        LifecycleCase staleCase = LifecycleCase.builder()
                .status("COMPLETED")
                .version(0) // 古いバージョン
                .build();
        staleCase.setId(lcCase.getId());
        int staleUpdated = caseMapper.updateById(staleCase);
        assertEquals(0, staleUpdated, "古いversionでの更新は0件（競合検知）になるはず");

        // タスクの楽観ロック検証
        LifecycleTask task = LifecycleTask.builder()
                .caseId(lcCase.getId())
                .taskCode("RESIGN_ACC_REVOKE")
                .taskName("アカウント無効化・セッション失効")
                .dueDate(today.plusDays(14))
                .assigneeRole("HR")
                .isMandatory(1)
                .isBlocking(1)
                .evidenceType("SYSTEM_CHECK")
                .isEngineerVisible(0)
                .status("IN_PROGRESS")
                .version(0)
                .build();
        taskMapper.insert(task);
        assertNotNull(task.getId());
        assertEquals(0, task.getVersion());

        task.setStatus("COMPLETED");
        task.setCompletedAt(LocalDateTime.now());
        task.setCompletedBy(1L);
        int taskUpdated = taskMapper.updateById(task);
        assertEquals(1, taskUpdated);
        assertEquals(1, task.getVersion());

        LifecycleTask staleTask = LifecycleTask.builder()
                .status("PENDING")
                .version(0)
                .build();
        staleTask.setId(task.getId());
        int staleTaskUpdated = taskMapper.updateById(staleTask);
        assertEquals(0, staleTaskUpdated, "古いversionでのタスク更新は0件になるはず");
    }

    @Test
    @Transactional
    @DisplayName("F1: 証跡文書リンクと追記イベント台帳の作成・取得ができる")
    void testEvidenceLinkAndLifecycleEvents() {
        LocalDate today = LocalDate.now();
        LifecycleCase lcCase = LifecycleCase.builder()
                .caseNo("LC-202608-TEST02")
                .lifecycleType("RESIGNATION")
                .engineerId(102L)
                .templateId(1L)
                .templateVersion(1)
                .anchorDate(today.plusDays(10))
                .status("ACTIVE")
                .title("鈴木花子 退社手続き")
                .applicantUserId(1L)
                .engineerSnapshotJson("{\"engineerId\":102,\"fullName\":\"鈴木花子\"}")
                .build();
        caseMapper.insert(lcCase);

        LifecycleTask task = LifecycleTask.builder()
                .caseId(lcCase.getId())
                .taskCode("RESIGN_DOC_CONFIRM")
                .taskName("退職届受理確認")
                .dueDate(today.plusDays(10))
                .assigneeRole("HR")
                .isMandatory(1)
                .isBlocking(1)
                .evidenceType("DOCUMENT_LINK")
                .isEngineerVisible(0)
                .status("IN_PROGRESS")
                .build();
        taskMapper.insert(task);

        LifecycleEvidenceLink link = LifecycleEvidenceLink.builder()
                .taskId(task.getId())
                .documentId(2001L)
                .documentVersionId(1L)
                .verifiedAt(LocalDateTime.now())
                .verifiedBy(1L)
                .remarks("退職届受理確認")
                .build();
        evidenceLinkMapper.insert(link);
        assertNotNull(link.getId());

        List<LifecycleEvidenceLink> links = evidenceLinkMapper.selectByTaskId(task.getId());
        assertEquals(1, links.size());
        assertEquals(2001L, links.get(0).getDocumentId());

        LifecycleEvent event = LifecycleEvent.builder()
                .caseId(lcCase.getId())
                .taskId(task.getId())
                .eventType("TASK_COMPLETED")
                .actorUserId(1L)
                .actorRoleSnapshot("HR")
                .beforeState("IN_PROGRESS")
                .afterState("COMPLETED")
                .detailsJson("{\"comment\":\"証跡確認完了\"}")
                .occurredAt(LocalDateTime.now())
                .build();
        eventMapper.insert(event);
        assertNotNull(event.getId());

        List<LifecycleEvent> events = eventMapper.selectByCaseId(lcCase.getId());
        assertEquals(1, events.size());
        assertEquals("TASK_COMPLETED", events.get(0).getEventType());
    }
}
