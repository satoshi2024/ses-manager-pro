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
}
