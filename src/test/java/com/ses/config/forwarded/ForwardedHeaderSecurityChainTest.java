package com.ses.config.forwarded;

import com.ses.entity.Menu;
import com.ses.service.AuditLogService;
import com.ses.service.MenuCacheService;
import com.ses.service.compliance.ComplianceRuleEngine;
import com.ses.service.compliance.LaborComplianceService;
import com.ses.service.security.AuthorizationService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FWD-SANITIZE PRE-P1-1: 実Spring Security chain（MockMvc。FilterRegistrationBeanの順序で前段filterも通る）で、
 * {@code X-Forwarded-Prefix} 付きの要求がmenu権限を素通りせず、更新系操作が監査されることを確かめる。
 *
 * <p>修正前は営業の {@code GET /api/compliance/findings}（m_menuで管理者・マネージャーだけに許可）に
 * {@code X-Forwarded-Prefix: /x} を付けると、MenuPermissionFilterが {@code /x/api/...} を判定対象外として通し、
 * Spring SecurityとMVCはprefixを除いたpathで照合するためcontrollerまで到達していた。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ForwardedHeaderSecurityChainTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext applicationContext;

    @MockBean
    private MenuCacheService menuCacheService;
    @MockBean
    private AuthorizationService authorizationService;
    @MockBean
    private AuditLogService auditLogService;
    @MockBean
    private LaborComplianceService laborComplianceService;
    @MockBean
    private ComplianceRuleEngine complianceRuleEngine;

    @BeforeEach
    void setUp() {
        Menu compliance = new Menu();
        compliance.setMenuKey("compliance");
        compliance.setPathPrefix("/compliance");
        compliance.setApiPrefix("/api/compliance");
        when(menuCacheService.getAllMenus()).thenReturn(List.of(compliance));
        when(menuCacheService.getMenuKeysByRole("営業")).thenReturn(List.of("engineer"));
        when(menuCacheService.getMenuKeysByRole("マネージャー")).thenReturn(List.of("compliance"));
        when(menuCacheService.getMenuKeysByRole("管理者")).thenReturn(List.of("compliance"));
        // baseline + deny: 営業の compliance.view はaction層では許可され、m_menuだけが遮断している
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(laborComplianceService.findCurrentRisks()).thenReturn(List.of());
        when(complianceRuleEngine.runActiveContracts()).thenReturn(new ComplianceRuleEngine.RunResult(0, 0, 0, 0));
    }

    @Test
    void Boot標準ではなく清浄化filterだけがForwardedHeaderFilterとして登録されている() {
        @SuppressWarnings("rawtypes")
        List<Filter> forwardedFilters = applicationContext.getBeansOfType(FilterRegistrationBean.class).values()
                .stream()
                .map(registration -> (Filter) registration.getFilter())
                .filter(filter -> filter instanceof ForwardedHeaderFilter)
                .toList();

        assertEquals(1, forwardedFilters.size(), forwardedFilters.toString());
        assertInstanceOf(ForwardedHeaderSanitizingFilter.class, forwardedFilters.get(0));
        assertTrue(applicationContext.getBeansOfType(ForwardedHeaderFilter.class).isEmpty());
    }

    @Test
    @WithMockUser(roles = "営業")
    void 営業はXForwardedPrefix付きでもmenuで禁止されたcomplianceAPIへ到達できない() throws Exception {
        for (String prefix : List.of("/x", "/mfa", "/css", "/api")) {
            mockMvc.perform(get("/api/compliance/findings").header("X-Forwarded-Prefix", prefix))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/compliance/findings"))
                .andExpect(status().isForbidden());
        verify(laborComplianceService, never()).findCurrentRisks();
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void menu許可のあるroleはXForwardedPrefix付きでも従来どおり到達できる() throws Exception {
        mockMvc.perform(get("/api/compliance/findings").header("X-Forwarded-Prefix", "/x"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void XForwardedPrefix付きの更新系操作も監査に記録される() throws Exception {
        mockMvc.perform(post("/api/compliance/rules/run").with(csrf()).header("X-Forwarded-Prefix", "/x"))
                .andExpect(status().isOk());

        verify(auditLogService).record(any(), eq("POST"), eq("/api/compliance/rules/run"), eq(200),
                eq("ses-manager"), eq(true));
    }
}
