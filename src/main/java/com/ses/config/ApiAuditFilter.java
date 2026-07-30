package com.ses.config;

import com.ses.common.util.SecurityInfrastructureUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.service.AuditLogService;
import com.ses.service.security.BreakGlassService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API操作ログフィルター
 * /api/** への更新系リクエスト（POST/PUT/DELETE）について、
 * 実行者・HTTPメソッド・URI・レスポンスステータスをアプリケーションログに記録し、
 * t_audit_log にも永続化する（アプリケーションログはローテーションで消えるため）。
 * 参照系（GET）は原則対象外。ただしファイルdownloadは成功・拒否とも記録する。
 *
 * AuditLogServiceはObjectProviderで任意依存にし、@WebMvcTest等の薄いテスト
 * コンテキストでBeanが無い場合でもフィルター自体は動作するようにする
 * （MenuPermissionFilter等の既存フィルターと同じ防御的な取得パターン）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAuditFilter extends OncePerRequestFilter {

    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
        boolean breakGlassRequest = isBreakGlassRequest(request);
        if (breakGlassRequest) {
            if (auditLogService == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
            try {
                auditLogService.recordRequired(SecurityUtils.currentUsername(), request.getMethod(),
                        request.getRequestURI(), 102, "BREAK_GLASS_ACCESS_ATTEMPT", false);
            } catch (RuntimeException e) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        int observedStatus = HttpServletResponse.SC_OK;
        try {
            filterChain.doFilter(request, response);
            observedStatus = response.getStatus();
        } catch (AccessDeniedException e) {
            observedStatus = HttpServletResponse.SC_FORBIDDEN;
            throw e;
        } catch (AuthenticationException e) {
            observedStatus = HttpServletResponse.SC_UNAUTHORIZED;
            throw e;
        } finally {
            if (isAuditTarget(request)) {
                String username = SecurityUtils.currentUsername();
                String method = request.getMethod();
                String uri = request.getRequestURI();
                int status = response.getStatus() >= 400 ? response.getStatus() : observedStatus;
                log.info("操作ログ user={} method={} uri={} status={}",
                        username != null ? username : "-", method, uri, status);
                if (auditLogService != null) {
                    String applicationCode = breakGlassRequest ? "BREAK_GLASS_ACCESS"
                            : "GET".equals(method) && uri.startsWith("/api/files/")
                            ? (status >= 400 ? "FILE_DOWNLOAD_REJECTED" : "FILE_DOWNLOAD")
                            : "ses-manager";
                    auditLogService.record(username, method, uri, status, applicationCode,
                            status >= 200 && status < 400);
                }
            }
        }
    }

    /**
     * 記録対象か判定する（/api/** かつ 更新系メソッド）。
     */
    private boolean isAuditTarget(HttpServletRequest request) {
        if (isBreakGlassRequest(request)) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            return false;
        }
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)
                || ("GET".equals(method) && uri.startsWith("/api/files/"));
    }

    private boolean isBreakGlassRequest(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE) == null) {
            return false;
        }
        return !SecurityInfrastructureUtils.isStaticResource(request)
                && !SecurityInfrastructureUtils.isErrorDispatch(request);
    }
}
