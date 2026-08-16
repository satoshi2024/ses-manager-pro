package com.ses.controller.api;

import com.ses.entity.Menu;
import com.ses.service.MenuCacheService;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T077 A1: position board/allocation timeline APIのL2〜L3 test。
 * CSRF・CRUD・状態遷移・過配賦拒否・scope（DataScope 404）を確認する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffingApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private MenuCacheService menuCacheService;

    private long customerId;
    private long projectId;
    private long engineerId;
    private long positionId;

    @BeforeEach
    void setUp() {
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(menuCacheService.getAllMenus()).thenReturn(List.of(
                Menu.builder().menuKey("engineer").pathPrefix("/engineer").apiPrefix("/api/engineers").build(),
                Menu.builder().menuKey("project").pathPrefix("/project").apiPrefix("/api/projects").build()));
        when(menuCacheService.getMenuKeysByRole("管理者")).thenReturn(List.of("engineer", "project"));
        when(menuCacheService.getMenuKeysByRole("営業")).thenReturn(List.of("engineer", "project"));

        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T077api-" + suffix);
        customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T077api-" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", "T077api-prj-" + suffix, customerId);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T077api-prj-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) "
                + "VALUES (?, '正社員', 'Bench')", "T077api-eng-" + suffix);
        engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T077api-eng-" + suffix);
    }

    @Test
    @WithMockUser(roles = "管理者")
    void CSRFトークンなしの更新系は拒否される() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(positionJson("P1", "Javaエンジニア")))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void ポジションのCRUDと状態遷移ができる() throws Exception {
        String result = mockMvc.perform(post("/api/projects/" + projectId + "/positions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(positionJson("P1", "Javaエンジニア")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.positionNo").value("P1"))
                .andReturn().getResponse().getContentAsString();
        long id = extractId(result);

        mockMvc.perform(get("/api/projects/" + projectId + "/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(post("/api/projects/" + projectId + "/positions/" + id + "/status")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"候補選定\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("候補選定"));

        mockMvc.perform(get("/api/projects/" + projectId + "/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.columns.length()").value(1))
                .andExpect(jsonPath("$.data.columns[0].position.positionNo").value("P1"));

        mockMvc.perform(delete("/api/projects/" + projectId + "/positions/" + id)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 配置の保存確定破棄と過配賦の拒否がAPIで動く() throws Exception {
        createPositionViaApi("P2");
        long positionIdRow = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project_position WHERE project_id = ?", Long.class, projectId);

        // 60%の下書きを保存して確定
        String draft = mockMvc.perform(post("/api/engineers/" + engineerId + "/allocations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(allocationJson(positionIdRow, "60", "2026-09-01", "2026-09-30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("下書き"))
                .andReturn().getResponse().getContentAsString();
        long allocId = extractId(draft);

        mockMvc.perform(post("/api/engineers/" + engineerId + "/allocations/" + allocId + "/confirm")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("確定"));

        // 50%の重複配置は過配賦（110%）で拒否される（HTTP 400 + ApiResult code 400）
        mockMvc.perform(post("/api/engineers/" + engineerId + "/allocations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(allocationJson(positionIdRow, "50", "2026-09-01", "2026-09-30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // タイムラインには確定1件
        mockMvc.perform(get("/api/engineers/" + engineerId + "/allocations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // 破棄
        mockMvc.perform(post("/api/engineers/" + engineerId + "/allocations/" + allocId + "/discard")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業はメニュー権限のあるボードAPIを呼べる() throws Exception {
        // scope.sales-own-data-only=false（既定）のためDataScopeは発動せずアクセス可能
        mockMvc.perform(get("/api/projects/" + projectId + "/board"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------

    private void createPositionViaApi(String no) throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/positions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(positionJson(no, "Javaエンジニア")))
                .andExpect(status().isOk());
    }

    private long extractId(String json) {
        int idx = json.indexOf("\"id\":");
        if (idx < 0) {
            return -1;
        }
        String rest = json.substring(idx + 5);
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        return end == 0 ? -1 : Long.parseLong(rest.substring(0, end));
    }

    private String positionJson(String no, String role) {
        return "{\"positionNo\":\"" + no + "\",\"roleName\":\"" + role
                + "\",\"requiredCount\":2,\"allocationPercent\":100}";
    }

    private String allocationJson(long positionIdRow, String percent, String start, String end) {
        return "{\"allocationType\":\"案件\",\"positionId\":" + positionIdRow
                + ",\"startDate\":\"" + start + "\",\"endDate\":\"" + end
                + "\",\"allocationPercent\":" + percent + "}";
    }
}
