package com.ses.service.ai.copilot.citation;

import com.ses.config.LoginUser;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.entity.SysUser;
import com.ses.service.RoleMenuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CitationAuthorizationServiceTest {

    @Mock
    private RoleMenuService roleMenuService;

    @InjectMocks
    private CitationAuthorizationServiceImpl citationAuthorizationService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 管理者はdashboardCitationを取得できる() {
        loginAs("admin", "管理者");
        ResolvedCitationDto citation = citationAuthorizationService.authorize("dashboard.summary");
        assertTrue(citation.available());
        assertTrue(citation.route().startsWith("/dashboard"));
    }

    @Test
    void 営業は管理会計Citationを拒否する() {
        loginAs("sales", "営業");

        ResolvedCitationDto citation = citationAuthorizationService.authorize("management-accounting.summary");
        assertFalse(citation.available());
    }

    @Test
    void HRは全Citationを拒否する() {
        loginAs("hr", "HR");
        ResolvedCitationDto citation = citationAuthorizationService.authorize("dashboard.summary");
        assertFalse(citation.available());
    }

    @Test
    void salesPerformanceはdisabledで拒否() {
        loginAs("admin", "管理者");
        ResolvedCitationDto citation = citationAuthorizationService.authorize("sales-performance.monthly");
        assertFalse(citation.available());
    }

    private void loginAs(String username, String role) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername(username);
        user.setRole(role);
        LoginUser loginUser = new LoginUser(user, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }
}
