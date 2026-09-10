package com.ses.controller.api;

import com.ses.config.LoginUser;
import com.ses.entity.ProjectIngestion;
import com.ses.entity.SysUser;
import com.ses.service.ProjectIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/** 案件取込APIがtenant-awareの安全DTOだけを返すことを検証する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectIngestionApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectIngestionService projectIngestionService;

    private RequestPostProcessor authentication(String tenantId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("operator");
        user.setRole("管理者");
        user.setTenantId(tenantId);
        LoginUser principal = new LoginUser(user,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void listAndDetailReturnTenantScopedSafeDto() throws Exception {
        ProjectIngestion job = new ProjectIngestion();
        job.setId(10L);
        job.setTenantId("default");
        job.setOriginalFileName("resume.eml");
        job.setStoredFileName("internal-storage-key.eml");
        job.setRawText("PII body");
        job.setStatus("要確認");
        job.setCreatedBy(99L);
        job.setVersion(2);
        doAnswer(invocation -> {
            Page<ProjectIngestion> page = invocation.getArgument(0);
            page.setRecords(List.of(job));
            page.setTotal(1);
            return page;
        }).when(projectIngestionService).pageForCurrentTenant(any(), eq("要確認"));
        when(projectIngestionService.getForCurrentTenant(10L)).thenReturn(job);

        mockMvc.perform(get("/api/project-ingestions")
                        .with(authentication("default"))
                        .param("status", "要確認"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records", hasSize(1)))
                .andExpect(jsonPath("$.data.records[0].rawText", is("PII body")))
                .andExpect(jsonPath("$.data.records[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].storedFileName").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].createdBy").doesNotExist());

        mockMvc.perform(get("/api/project-ingestions/10").with(authentication("default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(10)))
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.storedFileName").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void otherTenantDetailIsNotFound() throws Exception {
        when(projectIngestionService.getForCurrentTenant(10L)).thenReturn(null);

        mockMvc.perform(get("/api/project-ingestions/10").with(authentication("default")))
                .andExpect(status().isNotFound());
    }
}
