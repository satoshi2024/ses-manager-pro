package com.ses.config;

import com.ses.service.security.AccountLockService;
import com.ses.service.security.MfaService;
import com.ses.service.security.PersistentSessionService;
import com.ses.service.AuditLogService;
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

    /** 認証成功イベント監査。sliceで未配線の場合もログイン処理は継続する。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.AuditLogService auditLogService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        accountLockService.onLoginSuccess(authentication.getName());
        persistentSessionService.register(request, authentication);
        if (authentication instanceof UsernamePasswordAuthenticationToken && mfaService.isRequired(authentication)) {
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);
            session.removeAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE);
            setDefaultTargetUrl(mfaService.isConfigured(com.ses.common.util.SecurityUtils.currentUserId())
                    ? "/mfa/challenge" : "/mfa/setup");
        } else {
            setDefaultTargetUrl("/");
        }
        // session発行まで完了した認証だけを成功として監査する。
        if (auditLogService != null) {
            auditLogService.record(authentication.getName(), "AUTH", "/login", 200, "LOGIN_SUCCESS", true);
        }
        setAlwaysUseDefaultTargetUrl(true);
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
