package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.LoginUser;
import com.ses.entity.Project;
import com.ses.entity.SysUser;
import com.ses.service.ProjectService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 案件APIのテスト（P8 Task9）: 一覧・登録・バリデーションエラー・tenant境界。
 */
@WebMvcTest(ProjectApiController.class)
class ProjectApiControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private ProjectService projectService;

    @MockBean
    private com.ses.mapper.ProjectMapper projectMapper;

    @MockBean
    private com.ses.service.security.DataScopeService dataScopeService;
    @MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;
    @MockBean
    private com.ses.service.security.TenantOwnershipResolver tenantOwnershipResolver;

    @BeforeEach
    void allowFullOrganizationScope() {
        AccountingTenantContextHolder.setTenantId("default");
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(tenantOwnershipResolver.resolveCustomerIds("default")).thenReturn(Set.of(1L));
        when(tenantOwnershipResolver.resolveProjectIds("default")).thenReturn(Set.of(10L));
        when(tenantOwnershipResolver.selectCustomer("default", 1L)).thenReturn(new com.ses.entity.Customer());
        when(tenantOwnershipResolver.selectProject(eq("default"), eq(10L))).thenAnswer(inv -> {
            Project p = new Project();
            p.setId(10L);
            p.setCustomerId(1L);
            p.setProjectName("自tenant案件");
            return p;
        });
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void page_一覧は200() throws Exception {
        when(projectMapper.selectPageWithNames(any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(new Page<>());
        mockMvc.perform(get("/api/projects").with(tenantAuthentication("管理者")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void save_正常は200() throws Exception {
        Project p = new Project();
        p.setProjectName("金融システム開発");
        p.setCustomerId(1L);
        mockMvc.perform(post("/api/projects").with(tenantAuthentication("管理者")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(p)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void save_案件名空は400() throws Exception {
        Map<String, Object> body = Map.of("customerId", 1);
        mockMvc.perform(post("/api/projects").with(tenantAuthentication("管理者")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void save_終了日が開始日より前は400() throws Exception {
        Project p = new Project();
        p.setProjectName("案件");
        p.setCustomerId(1L);
        p.setStartDate(LocalDate.of(2026, 8, 1));
        p.setEndDate(LocalDate.of(2026, 7, 1));
        mockMvc.perform(post("/api/projects").with(tenantAuthentication("管理者")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(p)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void fullAccessでも他tenant案件のGETは404() throws Exception {
        when(tenantOwnershipResolver.selectProject("default", 99L)).thenReturn(null);

        mockMvc.perform(get("/api/projects/99").with(tenantAuthentication("管理者")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verify(projectService, never()).getById(99L);
    }

    @Test
    void fullAccessでも他tenant案件のPUTは404() throws Exception {
        when(tenantOwnershipResolver.selectProject("default", 99L)).thenReturn(null);
        Project body = new Project();
        body.setId(99L);
        body.setProjectName("侵入");
        body.setCustomerId(1L);

        mockMvc.perform(put("/api/projects").with(tenantAuthentication("管理者")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verify(projectService, never()).updateProjectWithSkills(any());
    }

    @Test
    void fullAccessでも他tenant案件のDELETEは404() throws Exception {
        when(tenantOwnershipResolver.selectProject("default", 99L)).thenReturn(null);

        mockMvc.perform(delete("/api/projects/99").with(tenantAuthentication("管理者")).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verify(projectService, never()).removeById(99L);
    }

    private RequestPostProcessor tenantAuthentication(String role) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("project-admin");
        user.setPassword("password");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId("default");
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
