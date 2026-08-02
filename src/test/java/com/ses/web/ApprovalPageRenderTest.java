package com.ses.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A1 Demo相当: 実際のSpring MVC/Thymeleafで3画面が描画できることを確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = "管理者")
class ApprovalPageRenderTest {
    @Autowired MockMvc mockMvc;
    @MockBean com.ses.service.RoleMenuService roleMenuService;

    @BeforeEach
    void setUp() {
        when(roleMenuService.getAllMenuKeys()).thenReturn(java.util.List.of("dashboard", "approval"));
        when(roleMenuService.getMenuKeysByRole("管理者")).thenReturn(java.util.List.of("dashboard", "approval"));
    }

    @Test
    void inboxAndRequestsRenderWithResponsiveMarkup() throws Exception {
        mockMvc.perform(get("/approval/inbox")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("approvalTable")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("table-responsive")));
        mockMvc.perform(get("/approval/requests")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-approval-view=\"mine\"")));
        mockMvc.perform(get("/approval/requests/1")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("approvalDiffBody")));
    }
}
