package com.ses.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 要員PWAから到達する動的・PIIレスポンスのブラウザHTTPキャッシュを禁止する。
 * Service Worker側も同じ境界でnetwork-onlyとし、controllerの追加漏れを防ぐ。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PwaNoStoreFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean noStore = isDynamicPath(request.getRequestURI());
        if (noStore) {
            applyNoStore(response);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (noStore) {
                // controllerがmax-age等を設定しても、動的データの保存を許可しない。
                applyNoStore(response);
            }
        }
    }

    static boolean isDynamicPath(String path) {
        return path != null && (path.startsWith("/api/")
                || path.equals("/api")
                || path.startsWith("/my/")
                || path.equals("/my")
                || path.startsWith("/portal/")
                || path.equals("/portal"));
    }

    private static void applyNoStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
    }
}
