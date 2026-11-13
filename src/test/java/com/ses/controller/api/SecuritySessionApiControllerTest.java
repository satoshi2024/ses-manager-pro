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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SecuritySessionApiControllerTest {

    @Mock
    private MfaService mfaService;
    @Mock
    private PersistentSessionService persistentSessionService;
    @Mock
    private com.ses.service.security.MfaAttemptService mfaAttemptService;
    @Mock
    private com.ses.service.AuditLogService auditLogService;

    private SecuritySessionApiController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new SecuritySessionApiController(mfaService, persistentSessionService,
                mfaAttemptService, auditLogService);
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

    @Test
    void MFA失敗はuserSessionSourceの試行guardへ記録する() {
        when(mfaService.isRequired(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(mfaService.verify(1L, "000000")).thenReturn(false);

        assertThrows(BusinessException.class, () -> controller.verify(
                new com.ses.dto.security.MfaCodeRequest("000000"),
                SecurityContextHolder.getContext().getAuthentication(), request));

        verify(mfaAttemptService).recordFailure(1L, request);
    }

    @Test
    void 重要監査が保存できなければMFA完了にしない() {
        when(mfaService.isRequired(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(mfaService.verify(1L, "123456")).thenReturn(true);
        doThrow(new IllegalStateException("audit unavailable")).when(auditLogService).recordRequired(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        assertThrows(IllegalStateException.class, () -> controller.verify(
                new com.ses.dto.security.MfaCodeRequest("123456"),
                SecurityContextHolder.getContext().getAuthentication(), request));

        org.junit.jupiter.api.Assertions.assertNull(request.getSession().getAttribute(
                MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE));
        verify(mfaAttemptService, never()).recordSuccess(1L, request);
    }

    @Test
    void MFA検証の成功は検証済みの印を本人のuser idへ結び付ける() {
        when(mfaService.isRequired(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(mfaService.verify(1L, "123456")).thenReturn(true);

        controller.verify(new com.ses.dto.security.MfaCodeRequest("123456"),
                SecurityContextHolder.getContext().getAuthentication(), request);

        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE,
                request.getSession().getAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE));
        org.junit.jupiter.api.Assertions.assertEquals(1L,
                request.getSession().getAttribute(MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE));
    }

    @Test
    void 別の主体のMFA検証済みの印ではTOTPを再登録できない() {
        // review P3-a: 同じブラウザで別の主体が検証した印を引き継いでも、本人の検証が済むまで既存TOTPを置き換えさせない
        when(mfaService.isRequired(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(mfaService.isConfigured(1L)).thenReturn(true);
        HttpSession session = request.getSession(true);
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE, 99L);

        assertThrows(BusinessException.class, () -> controller.setup(
                SecurityContextHolder.getContext().getAuthentication(), request));
        verify(mfaService, never()).setup(1L);

        // 本人（user id 1）が検証した印なら再登録できる
        session.setAttribute(MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE, 1L);
        controller.setup(SecurityContextHolder.getContext().getAuthentication(), request);
        verify(mfaService).setup(1L);
    }
}
