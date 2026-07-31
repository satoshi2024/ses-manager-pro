package com.ses.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ses.entity.Task;
import com.ses.service.TaskService;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @Test
    @WithMockUser(username = "1", roles = {"管理者"})
    void testGetTasks_authenticated_success() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "1", roles = {"営業"})
    void testCreateTask_withCsrf_success() throws Exception {
        Task task = new Task();
        task.setId(100L);
        task.setTitle("テストタスク");
        task.setAssigneeUserId(1L);
        given(taskService.createTask(any(), anyLong())).willReturn(task);

        mockMvc.perform(post("/api/tasks")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"テストタスク\",\"assigneeUserId\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "1", roles = {"営業"})
    void testCreateTask_withoutCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("{\"title\":\"テストタスク\",\"assigneeUserId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "1", roles = {"営業"})
    void testUpdateDetails_changeAssignee_preservesDueDate() throws Exception {
        Task updatedTask = new Task();
        updatedTask.setId(1L);
        updatedTask.setAssigneeUserId(2L);
        updatedTask.setDueDate(LocalDate.of(2026, 8, 10));

        given(taskService.updateTaskDetails(eq(1L), eq(2L), nullable(LocalDate.class), nullable(Boolean.class), nullable(String.class), eq(1L)))
                .willReturn(updatedTask);

        mockMvc.perform(put("/api/tasks/1/details")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"assigneeUserId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.assigneeUserId").value(2))
                .andExpect(jsonPath("$.data.dueDate").value("2026-08-10"));
    }

    @Test
    @WithMockUser(username = "1", roles = {"営業"})
    void testUpdateDetails_changeDueDate_preservesAssignee() throws Exception {
        Task updatedTask = new Task();
        updatedTask.setId(1L);
        updatedTask.setAssigneeUserId(1L);
        updatedTask.setDueDate(LocalDate.of(2026, 8, 15));

        given(taskService.updateTaskDetails(eq(1L), nullable(Long.class), eq(LocalDate.of(2026, 8, 15)), nullable(Boolean.class), nullable(String.class), eq(1L)))
                .willReturn(updatedTask);

        mockMvc.perform(put("/api/tasks/1/details")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"dueDate\":\"2026-08-15\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.assigneeUserId").value(1))
                .andExpect(jsonPath("$.data.dueDate").value("2026-08-15"));
    }

    @Test
    @WithMockUser(username = "1", roles = {"営業"})
    void testUpdateDetails_clearDueDate_setsDueDateNull() throws Exception {
        Task updatedTask = new Task();
        updatedTask.setId(1L);
        updatedTask.setAssigneeUserId(1L);
        updatedTask.setDueDate(null);

        given(taskService.updateTaskDetails(eq(1L), nullable(Long.class), nullable(LocalDate.class), eq(true), nullable(String.class), eq(1L)))
                .willReturn(updatedTask);

        mockMvc.perform(put("/api/tasks/1/details")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"clearDueDate\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dueDate").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @WithMockUser(username = "2", roles = {"営業"})
    void testUpdateDetails_otherUserTask_notFound() throws Exception {
        given(taskService.updateTaskDetails(eq(999L), nullable(Long.class), nullable(LocalDate.class), nullable(Boolean.class), nullable(String.class), eq(2L)))
                .willThrow(new com.ses.common.exception.BusinessException(404, "タスクが見つかりません"));

        mockMvc.perform(put("/api/tasks/999/details")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"assigneeUserId\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
