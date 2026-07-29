package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.MfaEnforcementFilter;
import com.ses.entity.SysUser;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecuritySessionApiControllerTest {

    @Mock
    private MfaService mfaService;
    @Mock
    private PersistentSessionService persistentSessionService;

    private SecuritySessionApiController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new SecuritySessionApiController(mfaService, persistentSessionService);
        request = new MockHttpServletRequest();
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("管理者");
        user.setStatus(1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginUser(user, List.of(() -> "ROLE_管理者")), "n/a", List.of(() -> "ROLE_管理者")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pending中の既存MFAユーザーはsetupでsecretを置換できない() {
        when(mfaService.isRequired(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(mfaService.isConfigured(1L)).thenReturn(true);
        HttpSession session = request.getSession(true);
        session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);

        assertThrows(BusinessException.class, () -> controller.setup(
                SecurityContextHolder.getContext().getAuthentication(), request));
    }

    @Test
    void 未検証の管理者は自分のMFAをresetできない() {
        HttpSession session = request.getSession(true);
        session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);

        assertThrows(BusinessException.class, () -> controller.reset(1L, request));
    }
}
