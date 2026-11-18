package com.ses.service.lifecycle.impl;

import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.service.lifecycle.LifecycleScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RB-POSTLAUNCH B-3: 案件一覧（社内 /lifecycle・本人 /my/lifecycle）の進捗が全件 0/0 と表示されていた。
 * 一覧のDTOにタスクも件数も載せず、画面が {@code tasks} から数えていたため。一覧は照会者に見えるタスクだけを数えた
 * 件数（totalTasks・completedTasks・progressPercent）を返す。詳細と同じく要員本人は本人公開タスクだけを数え、
 * 完了には免除を含める。タスクは一覧の案件分を1回で読む（案件ごとの検索をしない）。
 */
@ExtendWith(MockitoExtension.class)
class LifecycleCaseListProgressTest {

    @Mock
    private LifecycleCaseMapper caseMapper;

    @Mock
    private LifecycleTaskMapper taskMapper;

    @Mock
    private EngineerMapper engineerMapper;

    @Mock
    private LifecycleScopeService scopeService;

    @InjectMocks
    private LifecycleCaseServiceImpl service;

    /** 一覧のタスク検索は読む列を select で絞る（MyBatis-Plus が列名を解決するため、entity の metadata を用意する）。 */
    @org.junit.jupiter.api.BeforeEach
    void initializeMybatisLambdaMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), LifecycleTask.class);
    }

    @Test
    void 一覧は照会者に見えるタスクの完了数と総数を案件ごとに返す() {
        SysUser engineerUser = new SysUser();
        engineerUser.setId(5L);
        engineerUser.setRole("要員");
        engineerUser.setTenantId("tenant-a");
        when(scopeService.requireTenant(engineerUser)).thenReturn("tenant-a");
        when(caseMapper.selectList(any())).thenReturn(List.of(lifecycleCase(1L), lifecycleCase(2L)));
        Engineer engineer = Engineer.builder().tenantId("tenant-a").fullName("検証 二号").build();
        engineer.setId(300L);
        when(engineerMapper.selectList(any())).thenReturn(List.of(engineer));
        when(scopeService.canViewCase(any(), any(), any())).thenReturn(true);
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(1L, "COMPLETED", 1),
                task(1L, "IN_PROGRESS", 1),
                task(1L, "WAIVED", 1),
                task(1L, "PENDING", 1),
                task(1L, "COMPLETED", 0)));
        // 要員本人には本人公開のタスクだけが見える（詳細と同じ判定）
        when(scopeService.isTaskVisibleToUser(any(), any())).thenAnswer(invocation -> {
            LifecycleTask task = invocation.getArgument(1);
            return Integer.valueOf(1).equals(task.getIsEngineerVisible());
        });

        List<LifecycleCaseDto> cases = service.listCases(null, null, 300L, null, null, engineerUser);

        assertThat(cases).hasSize(2);
        LifecycleCaseDto first = cases.get(0);
        assertThat(first.getTotalTasks()).isEqualTo(4);
        assertThat(first.getCompletedTasks()).as("完了と免除を数え、非公開の完了タスクは数えない").isEqualTo(2);
        assertThat(first.getProgressPercent()).isEqualTo(50);
        LifecycleCaseDto second = cases.get(1);
        assertThat(second.getTotalTasks()).isZero();
        assertThat(second.getCompletedTasks()).isZero();
        assertThat(second.getProgressPercent()).isZero();
        @SuppressWarnings({"unchecked", "rawtypes"})
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> query =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(taskMapper, times(1)).selectList(query.capture());
        // 読む列は件数と可視判定に使うものだけ（説明・証跡JSON・完了コメント等の行全体を読まない。review P3-1）
        assertThat(query.getValue().getSqlSelect())
                .contains("case_id", "status", "is_engineer_visible", "assignee_role", "assignee_user_id")
                .doesNotContain("description", "evidence_data_json", "completion_comment", "task_name");
    }

    private static LifecycleCase lifecycleCase(Long id) {
        LifecycleCase lcCase = LifecycleCase.builder().tenantId("tenant-a").caseNo("LC-" + id).engineerId(300L)
                .lifecycleType("ASSIGNMENT").status("ACTIVE").build();
        lcCase.setId(id);
        return lcCase;
    }

    private static LifecycleTask task(Long caseId, String status, int engineerVisible) {
        return LifecycleTask.builder().caseId(caseId).taskCode("T").taskName("タスク").status(status)
                .isEngineerVisible(engineerVisible).build();
    }
}
