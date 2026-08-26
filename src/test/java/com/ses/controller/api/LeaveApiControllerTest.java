package com.ses.controller.api;

import com.ses.dto.leave.LeaveApplicationResult;
import com.ses.entity.Menu;
import com.ses.service.LeaveService;
import com.ses.service.MenuCacheService;
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

/** T071のAPI認可・CSRF境界を確認するL2定向test。営業はSecurityConfigで休暇APIへ到達できない。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LeaveApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LeaveService leaveService;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private MenuCacheService menuCacheService;

    @BeforeEach
    void allowMappedActionsForThisControllerSlice() {
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(menuCacheService.getAllMenus()).thenReturn(java.util.List.of(
                Menu.builder().menuKey("leave-management").pathPrefix("/leave").apiPrefix("/api/leave").build(),
                Menu.builder().menuKey("my-timesheet").pathPrefix("/my").apiPrefix("/api/my").build()));
        when(menuCacheService.getMenuKeysByRole("要員")).thenReturn(java.util.List.of("my-timesheet"));
        when(menuCacheService.getMenuKeysByRole("マネージャー")).thenReturn(java.util.List.of("leave-management"));
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業は休暇管理APIへ到達できない() throws Exception {
        mockMvc.perform(get("/api/leave").param("month", "2026-08"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業は休暇管理画面へ到達できない() throws Exception {
        mockMvc.perform(get("/leave"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void 管理一覧はサービスのscope済み結果を返す() throws Exception {
        when(leaveService.management("2026-08")).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/leave").param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(leaveService).management("2026-08");
    }

    @Test
    @WithMockUser(roles = "要員")
    void 本人申請はCSRFなしを拒否しCSRFありを通す() throws Exception {
        String body = "{\"leaveType\":\"有給\",\"startDate\":\"2026-08-03\",\"endDate\":\"2026-08-03\"}";
        when(leaveService.apply(any())).thenReturn(new LeaveApplicationResult(1L, 2L));

        mockMvc.perform(post("/api/my/leave")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/my/leave")
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.leaveId").value(1));
        verify(leaveService).apply(any());
    }
}
