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
public class SavedViewApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "1", roles = {"管理者"})
    void testGetSavedViews_authenticated_success() throws Exception {
        mockMvc.perform(get("/api/saved-views").param("pageKey", "engineer_list"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "1", roles = {"営業"})
    void testCreateSavedView_validInput_success() throws Exception {
        mockMvc.perform(post("/api/saved-views")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"pageKey\":\"engineer_list\",\"name\":\"マイビュー\",\"filterJson\":\"{\\\"prefecture\\\":\\\"Tokyo\\\"}\"}"))
                .andExpect(status().isOk());
    }
}
