package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.CostCenter;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.UserOrganization;
import com.ses.service.CostCenterService;
import com.ses.service.OrganizationService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.mapper.UserOrganizationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 組織APIの入力、CSRF、組織scope境界を検証する。 */
@WebMvcTest(OrganizationApiController.class)
class OrganizationApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrganizationService organizationService;

    @MockBean
    private OrganizationScopeService organizationScopeService;

    @MockBean
    private CostCenterService costCenterService;

    @MockBean
    private UserOrganizationMapper userOrganizationMapper;

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    void create_blankCode_returns400() throws Exception {
        String body = "{" +
                "\"name\":\"営業本部\",\"type\":\"部門\",\"validFrom\":\"2026-01-01\"}";

        mockMvc.perform(post("/api/organizations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    void create_validOrganization_withCsrf_returns200() throws Exception {
        when(organizationService.save(any(OrganizationUnit.class))).thenAnswer(invocation -> true);
        String body = "{\"code\":\"D01\",\"name\":\"営業本部\",\"type\":\"部門\",\"validFrom\":\"2026-01-01\"}";

        mockMvc.perform(post("/api/organizations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(organizationService).save(any(OrganizationUnit.class));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    void create_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"D01\",\"name\":\"営業本部\",\"type\":\"部門\",\"validFrom\":\"2026-01-01\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "manager", roles = {"マネージャー"})
    void list_usesScopeServiceQueryBoundary() throws Exception {
        when(organizationScopeService.listVisibleOrganizations(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/organizations").param("asOf", "2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(organizationScopeService).listVisibleOrganizations(null, LocalDate.of(2026, 7, 1));
    }

    @Test
    @WithMockUser(username = "manager", roles = {"マネージャー"})
    void get_outsideScope_returns400() throws Exception {
        doThrow(BusinessException.of("error.organization.scope.notAllowed"))
                .when(organizationScopeService).assertAllowedOrganization(eq(99L), isNull(LocalDate.class));

        mockMvc.perform(get("/api/organizations/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
