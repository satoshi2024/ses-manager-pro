package com.ses.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TaskApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "1", roles = {"管理者"})
    void testGetTasks_authenticated_success() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "1", roles = {"営業"})
    void testCreateTask_withCsrf_success() throws Exception {
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
}
