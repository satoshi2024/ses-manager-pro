package com.ses.controller.api;

import com.ses.entity.Menu;
import com.ses.service.MenuCacheService;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T077 A1: staffing APIのscope適用（DataScopeが拒否した場合は404へ変換）を検証する。
 * DataScopeServiceをmockして「scope外」を再現する（config依存にしない決定的なtest）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffingApiScopeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private javax.sql.DataSource dataSource;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private MenuCacheService menuCacheService;

    @MockBean
    private DataScopeService dataScopeService;

    @BeforeEach
    void setUp() {
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(menuCacheService.getAllMenus()).thenReturn(List.of(
                Menu.builder().menuKey("engineer").pathPrefix("/engineer").apiPrefix("/api/engineers").build(),
                Menu.builder().menuKey("project").pathPrefix("/project").apiPrefix("/api/projects").build()));
        when(menuCacheService.getMenuKeysByRole("営業")).thenReturn(List.of("engineer", "project"));
    }

    @Test
    @WithMockUser(roles = "営業")
    void DataScope外の案件ボードは404へ変換される() throws Exception {
        doThrow(com.ses.common.exception.BusinessException.of(404, "error.scope.notFound"))
                .when(dataScopeService).assertAllowedProject(anyLong());
        mockMvc.perform(get("/api/projects/123/board"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "営業")
    void DataScope外の要員タイムラインは404へ変換される() throws Exception {
        doThrow(com.ses.common.exception.BusinessException.of(404, "error.scope.notFound"))
                .when(dataScopeService).assertAllowedEngineer(anyLong());
        mockMvc.perform(get("/api/engineers/123/allocations"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "営業")
    void DataScope外の案件positionへの配置は404へ変換される() throws Exception {
        // positionの案件がDataScope外なら配置保存を拒否する（S12-R1-P1-06）
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO m_customer (company_name) VALUES ('scope-pos-cust')");
        long customerId = jdbc.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = 'scope-pos-cust'", Long.class);
        jdbc.update("INSERT INTO t_project (project_name, customer_id, status) VALUES ('scope-pos-prj', ?, '募集中')", customerId);
        long projectId = jdbc.queryForObject(
                "SELECT id FROM t_project WHERE project_name = 'scope-pos-prj'", Long.class);
        jdbc.update("INSERT INTO t_project_position "
                + "(project_id, position_no, role_name, required_count, allocation_percent, status, version) "
                + "VALUES (?, 'P1', 'Javaエンジニア', 1, 100, '募集中', 0)", projectId);
        long positionId = jdbc.queryForObject(
                "SELECT id FROM t_project_position WHERE project_id = ?", Long.class, projectId);

        doThrow(com.ses.common.exception.BusinessException.of(404, "error.scope.notFound"))
                .when(dataScopeService).assertAllowedProject(anyLong());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/engineers/123/allocations")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"allocationType\":\"案件\",\"positionId\":" + positionId
                                + ",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\",\"allocationPercent\":100}"))
                .andExpect(status().isNotFound());
    }
}
