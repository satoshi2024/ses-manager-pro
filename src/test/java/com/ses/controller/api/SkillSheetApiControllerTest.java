package com.ses.controller.api;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.skillsheet.SkillSheetGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = SkillSheetApiController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.ses.config.SecurityConfig.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.ses.config.InternalTenantContextFilter.class)
        }
)
@WithMockUser
class SkillSheetApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SkillSheetGenerator skillSheetGenerator;

    @MockBean
    private com.ses.service.security.DataScopeService dataScopeService;

    @MockBean
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @org.junit.jupiter.api.BeforeEach
    void allowFullOrganizationScope() {
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authentication() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("test");
        user.setRole("管理者");
        user.setTenantId("default");
        LoginUser principal = new LoginUser(user,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void downloadPdf_ShouldReturnPdfContentType() throws Exception {
        byte[] fakePdf = "%PDF-1.4...".getBytes();
        when(skillSheetGenerator.generatePdf(1L)).thenReturn(fakePdf);

        mockMvc.perform(get("/api/engineers/1/skill-sheet.pdf").with(authentication()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "form-data; name=\"attachment\"; filename=\"skill-sheet-1.pdf\""));
    }

    @Test
    void downloadExcel_ShouldReturnExcelContentType() throws Exception {
        byte[] fakeExcel = "PK...".getBytes();
        when(skillSheetGenerator.generateExcel(1L)).thenReturn(fakeExcel);

        mockMvc.perform(get("/api/engineers/1/skill-sheet.xlsx").with(authentication()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", "form-data; name=\"attachment\"; filename=\"skill-sheet-1.xlsx\""));
    }
}
