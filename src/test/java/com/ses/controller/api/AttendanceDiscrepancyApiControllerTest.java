package com.ses.controller.api;

import com.ses.dto.attendance.discrepancy.AttendanceDiscrepancyDto;
import com.ses.entity.Menu;
import com.ses.service.MenuCacheService;
import com.ses.service.attendance.AttendanceDiscrepancyService;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T073 B2: 客先工数差異APIのロール・CSRFを確認するL2定向test。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttendanceDiscrepancyApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AttendanceDiscrepancyService discrepancyService;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private MenuCacheService menuCacheService;

    @BeforeEach
    void setUp() {
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(menuCacheService.getAllMenus()).thenReturn(java.util.List.of(
                Menu.builder().menuKey("work-record").pathPrefix("/work-record").apiPrefix("/api/work-records").build()));
        when(menuCacheService.getMenuKeysByRole("管理者")).thenReturn(java.util.List.of("work-record"));
        when(menuCacheService.getMenuKeysByRole("HR")).thenReturn(java.util.List.of("work-record"));
        when(menuCacheService.getMenuKeysByRole("マネージャー")).thenReturn(java.util.List.of("work-record"));
        when(discrepancyService.list("2026-08"))
                .thenReturn(AttendanceDiscrepancyDto.empty("2026-08", 480));
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業は差異APIへ到達できない() throws Exception {
        mockMvc.perform(get("/api/work-records/attendance/discrepancy").param("month", "2026-08"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/work-records/attendance/discrepancy/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"engineerId\":\"1\",\"month\":\"2026-08\",\"reason\":\"x\"}")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "要員")
    void 要員は差異APIへ到達できない() throws Exception {
        mockMvc.perform(get("/api/work-records/attendance/discrepancy").param("month", "2026-08"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 管理者は一覧を取得できる() throws Exception {
        mockMvc.perform(get("/api/work-records/attendance/discrepancy").param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.workMonth").value("2026-08"));
        verify(discrepancyService).list("2026-08");
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void マネージャーは一覧と確認ができる() throws Exception {
        mockMvc.perform(get("/api/work-records/attendance/discrepancy").param("month", "2026-08"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/work-records/attendance/discrepancy/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"engineerId\":\"1\",\"month\":\"2026-08\",\"reason\":\"確認しました\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(discrepancyService).confirm(1L, "2026-08", "確認しました");
    }

    @Test
    @WithMockUser(roles = "管理者")
    void confirmはCSRFなしで403になる() throws Exception {
        mockMvc.perform(post("/api/work-records/attendance/discrepancy/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"engineerId\":\"1\",\"month\":\"2026-08\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
