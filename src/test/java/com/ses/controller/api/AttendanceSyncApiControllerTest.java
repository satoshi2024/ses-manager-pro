package com.ses.controller.api;

import com.ses.dto.attendance.sync.AttendanceSyncResultDto;
import com.ses.entity.Menu;
import com.ses.service.MenuCacheService;
import com.ses.service.attendance.AttendanceSyncService;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T072 B1: 外部同期APIのロール・CSRF・scopeを確認するL2定向test。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttendanceSyncApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AttendanceSyncService attendanceSyncService;

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
        when(attendanceSyncService.providerSource()).thenReturn("mock");
        when(attendanceSyncService.providerAvailable()).thenReturn(true);
        when(attendanceSyncService.lastResult()).thenReturn(AttendanceSyncResultDto.empty());
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業は同期APIへ到達できない() throws Exception {
        mockMvc.perform(post("/api/work-records/attendance/sync/run")
                        .param("month", "2026-08").param("direction", "push").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/work-records/attendance/sync/status"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/work-records/attendance/sync/export-csv").param("month", "2026-08"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "要員")
    void 要員は同期APIへ到達できない() throws Exception {
        mockMvc.perform(post("/api/work-records/attendance/sync/run")
                        .param("month", "2026-08").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void マネージャーはstatus閲覧はできるが_runは拒否される() throws Exception {
        mockMvc.perform(post("/api/work-records/attendance/sync/run")
                        .param("month", "2026-08").param("direction", "push").with(csrf()))
                .andExpect(status().isForbidden());
        verify(attendanceSyncService, never()).syncPush(anyString());

        mockMvc.perform(get("/api/work-records/attendance/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 管理者はrunを実行できる() throws Exception {
        AttendanceSyncResultDto result = AttendanceSyncResultDto.empty();
        result.setProvider("mock");
        result.setPushedCount(1);
        result.setSuccess(true);
        when(attendanceSyncService.syncPush("2026-08")).thenReturn(result);

        mockMvc.perform(post("/api/work-records/attendance/sync/run")
                        .param("month", "2026-08").param("direction", "push").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pushedCount").value(1));
        verify(attendanceSyncService).syncPush("2026-08");
    }

    @Test
    @WithMockUser(roles = "管理者")
    void runは不正なdirectionを400で拒否する() throws Exception {
        mockMvc.perform(post("/api/work-records/attendance/sync/run")
                        .param("month", "2026-08").param("direction", "bogus").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verify(attendanceSyncService, never()).syncPush(anyString());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void runはCSRFなしで403になる() throws Exception {
        mockMvc.perform(post("/api/work-records/attendance/sync/run")
                        .param("month", "2026-08").param("direction", "push"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HR")
    void HRはrunとCSV出力ができる() throws Exception {
        when(attendanceSyncService.syncAll("2026-08")).thenReturn(AttendanceSyncResultDto.empty());
        mockMvc.perform(post("/api/work-records/attendance/sync/run")
                        .param("month", "2026-08").param("direction", "all").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(attendanceSyncService).syncAll("2026-08");

        mockMvc.perform(get("/api/work-records/attendance/sync/export-csv").param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''")));
    }
}
