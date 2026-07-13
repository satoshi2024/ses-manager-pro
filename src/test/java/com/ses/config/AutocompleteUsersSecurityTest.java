package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/autocomplete/users が管理者限定であることの検証（WS-C R3）。
 * 営業ロールは403、管理者ロールは200を返すことを確認する。
 * また engineers エンドポイントは全ロールから引き続き利用可能であること。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutocompleteUsersSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "営業")
    void users_営業ロールは403() throws Exception {
        mockMvc.perform(get("/api/autocomplete/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void users_管理者ロールは200() throws Exception {
        mockMvc.perform(get("/api/autocomplete/users"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "営業")
    void engineers_営業ロールは200() throws Exception {
        mockMvc.perform(get("/api/autocomplete/engineers"))
                .andExpect(status().isOk());
    }
}
