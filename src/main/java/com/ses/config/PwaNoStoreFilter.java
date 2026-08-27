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
        if (path == null) return false;
        // i18n.jsは静的ファイルではなくlocale/queryで内容が変わるcontroller response。
        if (path.equals("/js/i18n.js")) return true;
        // PWAのshell allow-list以外はserver-rendered画面・APIを含めて動的扱いにする。
        // これにより /document、/payroll、PDF/attachment等の新規route追加時も
        // HTTP browser cacheへPIIが残る漏れを防ぐ（SWもdynamicはnetwork-only）。
        return !(path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/lib/")
                || path.startsWith("/img/")
                || path.startsWith("/data/")
                || path.equals("/favicon.ico")
                || path.equals("/favicon.svg")
                || path.equals("/manifest.webmanifest")
                || path.equals("/offline.html")
                || path.equals("/service-worker.js"));
    }

    private static void applyNoStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
    }
}
