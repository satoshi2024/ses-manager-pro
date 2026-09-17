package com.ses.controller.api;

import com.ses.dto.report.ReportDeliveryResult;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.report.ReportDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ManagementReportDeliveryApiControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private ReportDeliveryService deliveryService;

    @BeforeEach
    void seedMenu() {
        jdbcTemplate.update("DELETE FROM t_role_menu WHERE menu_id IN "
                + "(SELECT id FROM m_menu WHERE menu_key='management-report')");
        jdbcTemplate.update("DELETE FROM m_menu WHERE menu_key='management-report'");
        jdbcTemplate.update("INSERT INTO m_menu(menu_key, menu_name, path_prefix, api_prefix, sort_order) "
                + "VALUES('management-report','定期管理レポート','/management-reports','/api/management-reports',96)");
        for (String role : new String[]{"管理者", "マネージャー"}) {
            jdbcTemplate.update("INSERT INTO t_role_menu(role, menu_id) "
                    + "SELECT ?, id FROM m_menu WHERE menu_key='management-report'", role);
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void deliverはpreviewHash必須でserviceへ渡す() throws Exception {
        ReportRecipientPreviewResult preview = new ReportRecipientPreviewResult(
                "preview-hash", "APPROVED_SCOPE_CHECKED", LocalDateTime.now(),
                List.of(new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope")));
        when(deliveryService.deliverUser(10L, "preview-hash"))
                .thenReturn(new ReportDeliveryResult(preview, List.of()));

        mockMvc.perform(post("/api/management-reports/runs/10/deliver")
                        .param("previewHash", "preview-hash")
                        .with(tenantUser())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(deliveryService).deliverUser(10L, "preview-hash");
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void deliverはpreviewHash未指定を拒否する() throws Exception {
        mockMvc.perform(post("/api/management-reports/runs/10/deliver")
                        .with(tenantUser())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(deliveryService, never()).deliverUser(eq(10L), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void deliverはblankPreviewHashを拒否する() throws Exception {
        mockMvc.perform(post("/api/management-reports/runs/10/deliver")
                        .param("previewHash", "   ")
                        .with(tenantUser())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(deliveryService, never()).deliverUser(eq(10L), org.mockito.ArgumentMatchers.anyString());
    }

    private RequestPostProcessor tenantUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("admin123");
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId("default");
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
    }
}
