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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T078 B1: 需給heatmap APIのL2〜L3 test。
 * 24か月応答・horizon超過の拒否・HRのbench cost mask・drilldownを確認する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffingHeatmapApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private MenuCacheService menuCacheService;

    @BeforeEach
    void setUp() {
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(menuCacheService.getAllMenus()).thenReturn(List.of(
                Menu.builder().menuKey("analytics").pathPrefix("/analytics").apiPrefix("/api/analytics").build()));
        when(menuCacheService.getMenuKeysByRole("管理者")).thenReturn(List.of("analytics"));
        when(menuCacheService.getMenuKeysByRole("HR")).thenReturn(List.of("analytics"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void heatmapは24か月分の全社合計を返す() throws Exception {
        mockMvc.perform(get("/api/analytics/staffing-heatmap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totals.length()").value(24))
                .andExpect(jsonPath("$.data.role").isArray())
                .andExpect(jsonPath("$.data.skill").isArray())
                .andExpect(jsonPath("$.data.location").isArray());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 計画window24か月を超える要求は拒否される() throws Exception {
        String beyond = java.time.YearMonth.now().plusMonths(25).toString();
        mockMvc.perform(get("/api/analytics/staffing-heatmap")
                        .param("from", java.time.YearMonth.now().toString())
                        .param("to", beyond))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser(roles = "HR")
    void HRにはbenchCostがmaskされて返る() throws Exception {
        mockMvc.perform(get("/api/analytics/staffing-heatmap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals[0].benchCost").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void drilldownは需要と供給を返す() throws Exception {
        String month = java.time.YearMonth.now().toString();
        mockMvc.perform(get("/api/analytics/staffing-heatmap/drilldown")
                        .param("month", month)
                        .param("dimension", "role")
                        .param("group", "存在しないグループ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.positions").isArray())
                .andExpect(jsonPath("$.data.engineers").isArray());
    }
}
