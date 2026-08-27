package com.ses.controller.api;

import com.ses.dto.attendance.AttendanceOverviewDto;
import com.ses.entity.Menu;
import com.ses.service.AttendanceService;
import com.ses.service.MenuCacheService;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T070のAPI認可・CSRF境界を確認するL2定向test。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttendanceApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AttendanceService attendanceService;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private MenuCacheService menuCacheService;

    @BeforeEach
    void allowMappedActionsForThisControllerSlice() {
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(menuCacheService.getAllMenus()).thenReturn(java.util.List.of(
                Menu.builder().menuKey("my-timesheet").pathPrefix("/my").apiPrefix("/api/my").build(),
                Menu.builder().menuKey("work-record").pathPrefix("/work-record").apiPrefix("/api/work-records").build()));
        when(menuCacheService.getMenuKeysByRole("要員")).thenReturn(java.util.List.of("my-timesheet"));
        when(menuCacheService.getMenuKeysByRole("マネージャー")).thenReturn(java.util.List.of("work-record"));
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業は本人勤怠APIへ到達できない() throws Exception {
        mockMvc.perform(get("/api/my/attendance").param("month", "2026-08"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業は管理勤怠APIへ到達できない() throws Exception {
        mockMvc.perform(get("/api/work-records/attendance").param("month", "2026-08"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業は本人勤怠画面へ到達できない() throws Exception {
        mockMvc.perform(get("/my/attendance"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業は管理勤怠画面へ到達できない() throws Exception {
        mockMvc.perform(get("/work-record/attendance"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "要員")
    void 本人一覧はCSRF不要のGETでDTOを返す() throws Exception {
        AttendanceOverviewDto overview = new AttendanceOverviewDto();
        overview.setMonth("2026-08");
        overview.setMonths(java.util.List.of());
        when(attendanceService.mine("2026-08")).thenReturn(overview);

        mockMvc.perform(get("/api/my/attendance").param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.month").value("2026-08"));
    }

    @Test
    @WithMockUser(roles = "要員")
    void 日次保存はCSRFなしを拒否しCSRFありを通す() throws Exception {
        String body = "{\"workDate\":\"2026-08-03\",\"clockIn\":\"09:00\","
                + "\"clockOut\":\"18:00\",\"breakMinutes\":60,\"workType\":\"通常\"}";

        mockMvc.perform(post("/api/my/attendance/daily")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/my/attendance/daily")
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(attendanceService).saveMyDay(any());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void 管理画面はサービスのscope済み結果を返す() throws Exception {
        AttendanceOverviewDto overview = new AttendanceOverviewDto();
        overview.setMonth("2026-08");
        overview.setMonths(java.util.List.of());
        when(attendanceService.management(anyString(), any(), any())).thenReturn(overview);

        mockMvc.perform(get("/api/work-records/attendance").param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(attendanceService).management(eq("2026-08"), any(), any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 再openは理由付きJSONとCSRFを要求してサービスへ委譲する() throws Exception {
        String body = "{\"month\":\"2026-08\",\"reason\":\"訂正根拠\"}";

        mockMvc.perform(post("/api/work-records/attendance/42/reopen")
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(attendanceService).reopen(eq(42L), eq("2026-08"), eq("訂正根拠"));
    }
}
