package com.ses.config;

import com.ses.service.security.AccountLockService;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * ログイン成功ハンドラー。
 * 失敗回数・ロックをリセットし、ダッシュボードへ遷移する。
 */
@Component
@RequiredArgsConstructor
public class LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AccountLockService accountLockService;
    private final MfaService mfaService;
    private final PersistentSessionService persistentSessionService;
    private final OidcSecurityProperties oidcSecurityProperties;
    private final com.ses.service.security.BreakGlassService breakGlassService;

    /** 認証成功イベント監査。sliceで未配線の場合もログイン処理は継続する。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.AuditLogService auditLogService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        boolean breakGlass = authentication instanceof UsernamePasswordAuthenticationToken
                && oidcSecurityProperties.isBreakGlassUsername(authentication.getName());
        if (breakGlass) {
            if (!breakGlassService.isLoginAllowed(authentication.getName()) || auditLogService == null) {
                rejectBreakGlass(request, "break-glass incidentまたは監査が利用できません");
            }
            try {
                auditLogService.recordRequired(authentication.getName(), "AUTH", "/login", 200,
                        "BREAK_GLASS_LOGIN_SUCCESS", true);
            } catch (RuntimeException e) {
                rejectBreakGlass(request, "break-glass監査を永続化できません", e);
            }
        }
        accountLockService.onLoginSuccess(authentication.getName());
        persistentSessionService.register(request, authentication);
        // 遷移先はrequestごとのlocal変数で決める。setDefaultTargetUrl等はこのhandler
        // （singleton）のフィールドを書き換えるため、同時loginで遷移先が混ざる。
        String targetUrl = "/";
        if (authentication instanceof UsernamePasswordAuthenticationToken && mfaService.isRequired(authentication)) {
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);
            session.removeAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE);
            targetUrl = mfaService.isConfigured(com.ses.common.util.SecurityUtils.currentUserId())
                    ? "/mfa/challenge" : "/mfa/setup";
        }
        // session発行まで完了した認証だけを成功として監査する。
        if (!breakGlass && auditLogService != null) {
            auditLogService.record(authentication.getName(), "AUTH", "/login", 200, "LOGIN_SUCCESS", true);
        }
        clearAuthenticationAttributes(request);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private void rejectBreakGlass(HttpServletRequest request, String message) throws ServletException {
        rejectBreakGlass(request, message, null);
    }

    private void rejectBreakGlass(HttpServletRequest request, String message, RuntimeException cause)
            throws ServletException {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        throw cause == null ? new ServletException(message) : new ServletException(message, cause);
    }
}
