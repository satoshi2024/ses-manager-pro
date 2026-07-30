package com.ses.config;

import com.ses.common.util.SecurityInfrastructureUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.service.AuditLogService;
import com.ses.service.security.PersistentSessionService;
import com.ses.service.security.BreakGlassService;
import com.ses.service.security.BreakGlassService.BreakGlassDecision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 永続sessionが失効・期限切れなら、同じJSESSIONIDでの継続利用を拒否する。 */
@Component
@RequiredArgsConstructor
public class PersistentSessionFilter extends OncePerRequestFilter {

    private final ObjectProvider<PersistentSessionService> persistentSessionServiceProvider;
    private final ObjectProvider<BreakGlassService> breakGlassServiceProvider;
    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 静的リソース（/css/ /js/ /lib/ /img/ /favicon.ico）はDBアクセス・タッチおよびBreakGlass検証をスキップする
        if (SecurityInfrastructureUtils.isStaticResource(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        PersistentSessionService persistentSessionService = persistentSessionServiceProvider.getIfAvailable();
        if (persistentSessionService != null && !persistentSessionService.validateAndTouch(request, authentication)) {
            revokeSessionAndRespond401(request, response);
            return;
        }

        BreakGlassService breakGlassService = breakGlassServiceProvider.getIfAvailable();
        if (breakGlassService != null) {
            BreakGlassDecision decision = breakGlassService.validateBoundSession(request, authentication);
            if (decision == BreakGlassDecision.REVOKE) {
                revokeSessionAndRespond401(request, response);
                return;
            }
            if (decision == BreakGlassDecision.DENY_SCOPE) {
                AuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
                if (auditLogService == null) {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }
                try {
                    auditLogService.recordRequired(SecurityUtils.currentUsername(), request.getMethod(),
                            request.getRequestURI(), HttpServletResponse.SC_FORBIDDEN,
                            "BREAK_GLASS_SCOPE_VIOLATION", false);
                } catch (RuntimeException e) {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }
                response.setHeader("X-SES-Error-Code", "BREAK_GLASS_SCOPE_VIOLATION");
                if (request.getRequestURI().startsWith("/api/")) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    // レスポンスの message には message key ("error.breakGlass.scopeViolation") を直接設定。
                    // common.js / クライアント側で機械可読キー "BREAK_GLASS_SCOPE_VIOLATION" および X-SES-Error-Code ヘッダーからトースト抑止・エラー制御を行う。
                    response.getWriter().write("{\"code\":403,\"message\":\"error.breakGlass.scopeViolation\",\"data\":\"BREAK_GLASS_SCOPE_VIOLATION\"}");
                } else {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                }
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void revokeSessionAndRespond401(HttpServletRequest request, HttpServletResponse response) throws IOException {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"sessionが失効しています\",\"data\":null}");
        } else {
            response.sendRedirect(request.getContextPath() + "/login?sessionExpired");
        }
    }
}
