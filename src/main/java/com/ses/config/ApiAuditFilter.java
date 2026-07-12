package com.ses.config;

import com.ses.common.util.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API操作ログフィルター
 * /api/** への更新系リクエスト（POST/PUT/DELETE）について、
 * 実行者・HTTPメソッド・URI・レスポンスステータスをアプリケーションログに記録する。
 * 参照系（GET）は記録対象外。
 */
@Slf4j
@Component
public class ApiAuditFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (isAuditTarget(request)) {
                String username = SecurityUtils.currentUsername();
                log.info("操作ログ user={} method={} uri={} status={}",
                        username != null ? username : "-",
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus());
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
