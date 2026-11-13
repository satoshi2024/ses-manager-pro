package com.ses.config;

import com.ses.common.util.SecurityInfrastructureUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.service.AuditLogService;
import com.ses.service.security.ActionPermissionResolver;
import com.ses.service.security.MfaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * bootstrap session（{@link ProductionBootstrapMode}）の順序と到達範囲を強制する。
 *
 * <ol>
 *   <li>パスワード変更が済むまでは {@link PasswordChangeEnforcementFilter} が変更画面・変更APIだけを通す（本filterは介入しない）。</li>
 *   <li>変更後、MFA（TOTP）の登録または検証が済むまでは、MFA画面・MFA APIだけを通す
 *       （画面は {@code /mfa/setup}（未登録）または {@code /mfa/challenge}（登録済み）へredirect、APIは403）。</li>
 *   <li>MFA完了後も、bootstrapに必要な入口だけを通し、それ以外は403にする（APIはApiResult JSON、画面は統一エラー画面）。
 *       {@code GET /} だけはユーザー管理画面へredirectする。</li>
 * </ol>
 *
 * <p>入口の判定は既存の {@link ActionPermissionResolver} のaction keyとURI判定をそのまま使う。
 * ここでの制限はbootstrap sessionだけに掛かる意図的な一時制限であり、業務権限（baseline + deny）の
 * allow-list化ではない。通常のsession・OIDCのsessionには一切作用しない。
 *
 * <p>期限切れのbootstrap sessionは {@link PersistentSessionFilter} が先に失効させる。本filterも念のため
 * 期限を再確認し、期限外なら401で拒否する（fail-closed）。
 *
 * <p>依存beanは {@link ObjectProvider} で取得する（{@code @WebMvcTest} 等のsliceでは不在のため）。
 * bootstrap sessionの印が無い要求は何もせず通す。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapSessionFilter extends OncePerRequestFilter {

    /** MFA完了後に {@code GET /} を誘導する先（bootstrapの作業画面）。 */
    static final String LANDING_PAGE = "/user/list";

    /**
     * bootstrap sessionで到達してよい業務action keyのresource（action keyの {@code .} より前）。
     * <ul>
     *   <li>{@code user}: 2・3人目の管理者の作成（ユーザーCRUD・状態変更）</li>
     *   <li>{@code permission}: 権限group・role別menu（{@code permission.manage}）</li>
     *   <li>{@code identity-provider}: IdPの登録と外部identity linkの登録・承認</li>
     * </ul>
     */
    static final Set<String> BOOTSTRAP_RESOURCES = Set.of("user", "permission", "identity-provider");

    /** ユーザー一覧画面の検索候補（ユーザー管理画面が使う管理者専用の候補API）。 */
    private static final String USER_SUGGEST_API = "/api/autocomplete/users";

    private final ObjectProvider<ProductionBootstrapMode> bootstrapModeProvider;
    private final ObjectProvider<MfaService> mfaServiceProvider;
    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!ProductionBootstrapMode.isBootstrapSession(session) || authentication == null
                || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken
                || SecurityInfrastructureUtils.isStaticResource(request)
                || SecurityInfrastructureUtils.isErrorDispatch(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        ProductionBootstrapMode mode = bootstrapModeProvider.getIfAvailable();
        if (mode == null || !mode.isActive()) {
            rejectExpired(request, response, session);
            return;
        }

        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        String uri = request.getRequestURI();
        boolean passwordChangeRequired = PasswordChangeEnforcementFilter.isChangeRequired(session);
        boolean mfaVerified = Boolean.TRUE.equals(session.getAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE));

        // 改密 → MFAの順。改密前は PasswordChangeEnforcementFilter が既に変更経路以外を止めている。
        if (!passwordChangeRequired && !mfaVerified && !isMfaFlow(method, uri)) {
            requireMfa(request, response);
            return;
        }
        if (!passwordChangeRequired && mfaVerified && "GET".equals(method) && "/".equals(uri)) {
            response.sendRedirect(request.getContextPath() + LANDING_PAGE);
            return;
        }
        if (!isBootstrapEntry(request)) {
            deny(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * bootstrap sessionで到達してよい入口か。
     * 静的資源（{@code /js/i18n.js} を含む）・ERRORディスパッチ、認証継続の基盤endpoint（logout・MFA・本人session）、
     * 初回パスワード変更の入口、ユーザー管理画面の検索候補、および {@link #BOOTSTRAP_RESOURCES} のaction key。
     */
    static boolean isBootstrapEntry(HttpServletRequest request) {
        if (SecurityInfrastructureUtils.isStaticResource(request) || SecurityInfrastructureUtils.isErrorDispatch(request)) {
            return true;
        }
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        String uri = request.getRequestURI();
        if (ActionPermissionResolver.isAuthenticationInfrastructure(method, uri)
                || PasswordChangeEnforcementFilter.isChangeEndpoint(method, uri)
                || ("GET".equals(method) && USER_SUGGEST_API.equals(uri))) {
            return true;
        }
        String action = ActionPermissionResolver.resolve(method, uri);
        if (action == null) {
            return false;
        }
        int separator = action.indexOf('.');
        String resource = separator > 0 ? action.substring(0, separator) : action;
        return BOOTSTRAP_RESOURCES.contains(resource);
    }

    /** MFA完了前に通すMFA登録・検証の経路（{@link MfaEnforcementFilter} と同じ範囲）。 */
    private static boolean isMfaFlow(String method, String uri) {
        if ("GET".equals(method)) {
            return uri.equals("/mfa/setup") || uri.equals("/mfa/challenge") || uri.equals("/api/security/mfa/status");
        }
        return "POST".equals(method) && (uri.equals("/logout") || uri.equals("/api/security/mfa/setup")
                || uri.equals("/api/security/mfa/enable") || uri.equals("/api/security/mfa/verify"));
    }

    private void requireMfa(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    "{\"code\":403,\"message\":\"MFA認証が必要です\",\"data\":null}");
            return;
        }
        response.sendRedirect(request.getContextPath() + (isMfaConfigured() ? "/mfa/challenge" : "/mfa/setup"));
    }

    private boolean isMfaConfigured() {
        MfaService mfaService = mfaServiceProvider.getIfAvailable();
        Long userId = SecurityUtils.currentUserId();
        if (mfaService == null || userId == null) {
            return false;
        }
        try {
            return mfaService.isConfigured(userId);
        } catch (RuntimeException e) {
            log.warn("[bootstrap] MFA登録状態を確認できないためMFA登録画面へ誘導します", e);
            return false;
        }
    }

    private void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
        audit(request, HttpServletResponse.SC_FORBIDDEN, "BOOTSTRAP_SCOPE_DENIED");
        response.setHeader("X-SES-Error-Code", "BOOTSTRAP_SCOPE_VIOLATION");
        if (request.getRequestURI().startsWith("/api/")) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    "{\"code\":403,\"message\":\"bootstrap中はこの操作を利用できません\",\"data\":\"BOOTSTRAP_SCOPE_VIOLATION\"}");
        } else {
            // 画面遷移はエラーディスパッチ経由で統一エラーページ(CustomErrorController)を描画する
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    private void rejectExpired(HttpServletRequest request, HttpServletResponse response, HttpSession session)
            throws IOException {
        audit(request, HttpServletResponse.SC_UNAUTHORIZED, "BOOTSTRAP_SESSION_EXPIRED");
        session.invalidate();
        SecurityContextHolder.clearContext();
        if (request.getRequestURI().startsWith("/api/")) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "{\"code\":401,\"message\":\"bootstrapの期限が過ぎています\",\"data\":null}");
        } else {
            response.sendRedirect(request.getContextPath() + "/login?sessionExpired");
        }
    }

    private void audit(HttpServletRequest request, int status, String applicationCode) {
        AuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.record(SecurityUtils.currentUsername(), request.getMethod(), request.getRequestURI(),
                    status, applicationCode, false);
        } catch (RuntimeException e) {
            log.warn("[bootstrap] 監査ログを記録できませんでした: code={}", applicationCode, e);
        }
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }
}
