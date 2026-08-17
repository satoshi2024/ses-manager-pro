package com.ses.expense;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 経費APIのMVC境界テスト（T091 B1）。
 * 管理APIは@PreAuthorize+MenuPermissionFilterで管理者/マネージャーのみ有効で、
 * 営業・HRは403になること。本人API・画面は要員が到達できることを固定する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpenseApiSecurityMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "999001", roles = "管理者")
    void 管理者は管理APIと一覧画面に到達できる() throws Exception {
        mockMvc.perform(get("/api/expense-requests"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/expenses"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "999002", roles = "マネージャー")
    void マネージャーは管理APIに到達できる() throws Exception {
        mockMvc.perform(get("/api/expense-requests"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/expense-requests/1"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/expenses"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "999003", roles = "営業")
    void 営業は管理APIに到達できない() throws Exception {
        mockMvc.perform(get("/api/expense-requests"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/expenses"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "999004", roles = "HR")
    void HRは管理APIに到達できない() throws Exception {
        mockMvc.perform(get("/api/expense-requests"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "999005", roles = "要員")
    void 要員は本人経費画面に到達でき未紐付けは403である() throws Exception {
        mockMvc.perform(get("/my/expenses"))
                .andExpect(status().isOk());
        // username=999005は要員リンクが無いためAPIは未紐付け403
        mockMvc.perform(get("/api/my/expenses"))
                .andExpect(status().isForbidden());
    }
}
