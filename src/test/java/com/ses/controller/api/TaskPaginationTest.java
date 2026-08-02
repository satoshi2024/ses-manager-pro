package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.task.TaskListDto;
import com.ses.mapper.NotificationMapper;
import com.ses.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskApiController.class)
@DisplayName("ToDo Task pagination APIテスト (R3-015)")
class TaskPaginationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @MockBean
    private NotificationMapper notificationMapper;

    @Test
    @WithMockUser(username = "1", roles = "営業")
    @DisplayName("tasks/pageは担当者名を含むPage<TaskListDto>を返す")
    void getTasksPageReturnsPagedResultWithAssigneeName() throws Exception {
        Page<TaskListDto> page = new Page<>(1, 20, 81);
        TaskListDto dto = TaskListDto.builder()
                .id(1L)
                .title("契約更新リマインド")
                .status("NOT_STARTED")
                .priority("HIGH")
                .dueDate(LocalDate.now().plusDays(3))
                .assigneeUserId(2L)
                .assigneeUserName("営業 担当者")
                .overdue(false)
                .build();
        page.setRecords(List.of(dto));

        when(taskService.getTasksPage(eq(null), eq(null), eq(null), eq(null), eq(null), eq(1L), eq(20L), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/tasks/page")
                        .param("current", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(81))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.records[0].assigneeUserName").value("営業 担当者"));
    }
}
