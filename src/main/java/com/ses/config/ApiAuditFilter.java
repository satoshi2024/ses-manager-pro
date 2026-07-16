package com.ses.config;

import com.ses.common.util.SecurityUtils;
import com.ses.service.AuditLogService;
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
 * 参照系（GET）は記録対象外。
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
                AuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
                if (auditLogService != null) {
                    auditLogService.record(username, method, uri, status, "ses-manager", status >= 200 && status < 400);
                }
            }
        }
    }

    /**
     * 記録対象か判定する（/api/** かつ 更新系メソッド）。
     */
    private boolean isAuditTarget(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            return false;
        }
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
    }
}
