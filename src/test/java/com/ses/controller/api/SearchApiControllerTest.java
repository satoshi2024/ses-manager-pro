package com.ses.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class SearchApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "1", roles = {"管理者"})
    void testSearchApi_admin_success() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "テスト"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "2", roles = {"営業"})
    void testSearchApi_sales_success() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "テスト"))
                .andExpect(status().isOk());
    }

    @Test
    void testSearchApi_unauthenticated_unauthorized() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "テスト"))
                .andExpect(status().is4xxClientError());
    }
}
