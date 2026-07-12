package com.ses.config;

import com.ses.service.WorkRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 月次確定解除（/api/work-records/reopen）の権限検証。
 * 管理者のみ実行可能で、一般ロールは403になることを確認する
 * （@EnableMethodSecurity + SecurityConfig requestMatchers の二重防御）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkRecordReopenSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkRecordService workRecordService;

    @Test
    @WithMockUser(roles = "営業")
    void reopen_一般ロールは403() throws Exception {
        mockMvc.perform(post("/api/work-records/reopen").param("month", "2026-07").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void reopen_管理者は実行できる() throws Exception {
        mockMvc.perform(post("/api/work-records/reopen").param("month", "2026-07").with(csrf()))
                .andExpect(status().isOk());
    }
}
