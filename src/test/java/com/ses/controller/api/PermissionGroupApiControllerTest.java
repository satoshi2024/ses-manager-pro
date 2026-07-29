package com.ses.controller.api;

import com.ses.service.security.AuthorizationService;
import com.ses.service.security.MfaService;
import com.ses.service.security.PermissionGroupManagementService;
import com.ses.service.security.PersistentSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PermissionGroupApiController.class)
class PermissionGroupApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private PermissionGroupManagementService permissionGroupManagementService;
    @MockBean private AuthorizationService authorizationService;
    @MockBean private MfaService mfaService;
    @MockBean private PersistentSessionService persistentSessionService;

    @BeforeEach
    void setUp() {
        when(authorizationService.isAllowed(any(), any())).thenReturn(true);
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);
    }

    @Test
    @WithMockUser(roles = "営業")
    void group割当は管理者以外を拒否する() throws Exception {
        when(authorizationService.isAllowed(any(), org.mockito.ArgumentMatchers.eq("permission.manage")))
                .thenReturn(false);
        mockMvc.perform(put("/api/permission-groups/users/7").with(csrf())
                        .contentType("application/json")
                        .content("{\"groupIds\":[10]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void group割当はcsrfを必須にする() throws Exception {
        mockMvc.perform(put("/api/permission-groups/users/7")
                        .contentType("application/json")
                        .content("{\"groupIds\":[10]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void 管理者はgroup割当を更新できる() throws Exception {
        mockMvc.perform(put("/api/permission-groups/users/7").with(csrf())
                        .contentType("application/json")
                        .content("{\"groupIds\":[10]}"))
                .andExpect(status().isOk());

        verify(permissionGroupManagementService).replaceAssignments(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(Set.of(10L)), any());
    }
}
