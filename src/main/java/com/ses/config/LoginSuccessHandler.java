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

    /** 本番初回の期限付きbootstrap mode。slice等で不在ならbootstrap sessionを発行しない。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionBootstrapMode productionBootstrapMode;

    /**
     * 前の認証主体のsessionから引き継いではならない安全状態の属性（review P3-a）。
     * session fixation対策（{@code ChangeSessionIdAuthenticationStrategy}）はsession IDだけを替えて属性を残すため、
     * 同じブラウザでbootstrap session等から別の主体（例: OIDCの管理者）がloginすると、bootstrapの印・MFA検証済み・
     * 初回パスワード変更要求がそのまま新しい主体へ引き継がれる。loginのたびに消し、必要なものだけを以下で立て直す。
     */
    static final java.util.List<String> AUTHENTICATION_SCOPED_ATTRIBUTES = java.util.List.of(
            ProductionBootstrapMode.SESSION_ATTRIBUTE,
            ProductionBootstrapMode.SESSION_USER_ATTRIBUTE,
            MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE,
            MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE,
            MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE,
            PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE);

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        clearAuthenticationScopedAttributes(request);
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
        // 本番初回の期限付きbootstrap（初期管理者adminのlocal login）。認証後に期限が過ぎた場合もここで止める。
        boolean bootstrap = !breakGlass && isBootstrapLogin(authentication);
        if (bootstrap) {
            if (!productionBootstrapMode.isActive() || auditLogService == null) {
                recordBestEffort(authentication.getName(), 401, "BOOTSTRAP_LOGIN_EXPIRED");
                rejectBreakGlass(request, "bootstrapの期限が過ぎているか監査が利用できません");
            }
            try {
                auditLogService.recordRequired(authentication.getName(), "AUTH", "/login", 200,
                        "BOOTSTRAP_LOGIN_SUCCESS", true);
            } catch (RuntimeException e) {
                rejectBreakGlass(request, "bootstrap loginの監査を永続化できません", e);
            }
        }
        accountLockService.onLoginSuccess(authentication.getName());
        persistentSessionService.register(request, authentication);
        if (breakGlass && !breakGlassService.bindSession(request, authentication.getName())) {
            persistentSessionService.revokeCurrent(request, authentication, "BREAK_GLASS_INCIDENT_UNAVAILABLE");
            rejectBreakGlass(request, "break-glass incidentをsessionへ固定できません");
        }
        if (bootstrap) {
            // BootstrapSessionFilterが改密 → MFA → bootstrap入口だけの順に制限する。MFA pendingは立てない
            // （立てるとPasswordChangeEnforcementFilterがMFAを先に待つため、改密 → MFAの順にならない）。
            // 印はbootstrap loginの本人（sys_user.id）へ結び付け、別の主体のsessionでは効かせない（review P3-a）。
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            session.setAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE, Boolean.TRUE);
            session.setAttribute(ProductionBootstrapMode.SESSION_USER_ATTRIBUTE, principalUserId(authentication));
            session.removeAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE);
            session.removeAttribute(MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE);
        }
        // 遷移先はrequestごとのlocal変数で決める。setDefaultTargetUrl等はこのhandler
        // （singleton）のフィールドを書き換えるため、同時loginで遷移先が混ざる。
        String targetUrl = "/";
        // 初回パスワード変更（配備時の初期admin）。local password loginだけが対象で、MFAがあればMFAを先に完了させる。
        boolean passwordChangeRequired = authentication instanceof UsernamePasswordAuthenticationToken
                && isPasswordChangeRequired(authentication);
        if (passwordChangeRequired) {
            request.getSession(true).setAttribute(
                    PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE, Boolean.TRUE);
            targetUrl = PasswordChangeEnforcementFilter.CHANGE_PAGE;
        }
        if (authentication instanceof UsernamePasswordAuthenticationToken && mfaService.isRequired(authentication)) {
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            session.setAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE, Boolean.TRUE);
            session.removeAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE);
            session.removeAttribute(MfaEnforcementFilter.MFA_VERIFIED_USER_ATTRIBUTE);
            targetUrl = mfaService.isConfigured(com.ses.common.util.SecurityUtils.currentUserId())
                    ? "/mfa/challenge" : "/mfa/setup";
        } else if (bootstrap && !passwordChangeRequired) {
            // bootstrapは改密 → MFAの順。改密済みならMFAの登録または検証へ進める。
            targetUrl = mfaService.isConfigured(com.ses.common.util.SecurityUtils.currentUserId())
                    ? "/mfa/challenge" : "/mfa/setup";
        }
        // session発行まで完了した認証だけを成功として監査する。
        if (!breakGlass && !bootstrap && auditLogService != null) {
            auditLogService.record(authentication.getName(), "AUTH", "/login", 200, "LOGIN_SUCCESS", true);
        }
        clearAuthenticationAttributes(request);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    /** 前の認証主体から引き継いだ安全状態の属性を消す（既存sessionが無ければ何もしない）。 */
    private static void clearAuthenticationScopedAttributes(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        for (String attribute : AUTHENTICATION_SCOPED_ATTRIBUTES) {
            session.removeAttribute(attribute);
        }
    }

    /** 認証主体の {@code sys_user.id}（LoginUserでなければnull。nullの印はどの主体にも一致しない）。 */
    private static Long principalUserId(Authentication authentication) {
        return authentication.getPrincipal() instanceof LoginUser loginUser && loginUser.getSysUser() != null
                ? loginUser.getSysUser().getId() : null;
    }

    /** 認証時に読み込んだ {@code sys_user.password_change_required} が1か（DBを再読込しない）。 */
    private static boolean isPasswordChangeRequired(Authentication authentication) {
        return authentication.getPrincipal() instanceof LoginUser loginUser
                && loginUser.getSysUser() != null
                && Integer.valueOf(1).equals(loginUser.getSysUser().getPasswordChangeRequired());
    }

    /** prodでbootstrapが有効化されている間の、初期管理者adminのlocal password loginか。 */
    private boolean isBootstrapLogin(Authentication authentication) {
        return authentication instanceof UsernamePasswordAuthenticationToken && productionBootstrapMode != null
                && productionBootstrapMode.isRequested()
                && productionBootstrapMode.isBootstrapUsername(authentication.getName());
    }

    private void recordBestEffort(String username, int status, String applicationCode) {
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.record(username, "AUTH", "/login", status, applicationCode, false);
        } catch (RuntimeException ignored) {
            // 拒否は監査の成否に依存させない（fail-closed）
        }
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
