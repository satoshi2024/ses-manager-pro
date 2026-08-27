package com.ses.expense;

import com.ses.service.MenuCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class ExpenseApiSecurityMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MenuCacheService menuCacheService;

    @BeforeEach
    void restoreExpenseManagementMenus() {
        // 共有 H2 + 乱数順で、他テストが t_role_menu / m_menu を汚しても境界が崩れないように再固定する
        jdbcTemplate.update(
                "INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) "
                        + "SELECT 'expenseManagement', '経費管理', '/expenses', '/api/expense-requests', 100 "
                        + "WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'expenseManagement')");
        Long menuId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_menu WHERE menu_key = 'expenseManagement'", Long.class);
        jdbcTemplate.update("DELETE FROM t_role_menu WHERE menu_id = ? AND role IN ('営業', 'HR', '要員')", menuId);
        jdbcTemplate.update(
                "INSERT INTO t_role_menu (role, menu_id) "
                        + "SELECT r.role, ? FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー') r "
                        + "WHERE NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = ?)",
                menuId, menuId);
        menuCacheService.invalidate();
    }

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
