package com.ses.common.util;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 静的リソースやエラーディスパッチなどの基盤判定ユーティリティ。
 * セキュリティフィルター・監査・MFA・BreakGlass処理で判定ロジックを一元管理する。
 * <p>どちらもMFA強制・初回パスワード変更・bootstrap・break-glassのallow-listに使うため、
 * {@code getRequestURI()} ではなくアプリ内path（{@link RequestPaths}）で判定する。{@code X-Forwarded-Prefix: /css}
 * のような前置で {@code /css/api/...} と誤一致させないためである（FWD-SANITIZE PRE-P1-1）。
 */
public final class SecurityInfrastructureUtils {

    private SecurityInfrastructureUtils() {
    }

    /**
     * GET/HEADの静的アセット（/css/ /js/ /lib/ /img/ /favicon.ico）か判定する。
     */
    public static boolean isStaticResource(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String method = request.getMethod();
        if (!("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))) {
            return false;
        }
        String uri = RequestPaths.pathWithinApplication(request);
        return uri != null && (uri.startsWith("/css/") || uri.startsWith("/js/")
                || uri.startsWith("/lib/") || uri.startsWith("/img/")
                || uri.equals("/favicon.ico"));
    }

    /**
     * ERRORディスパッチ（/error）か判定する。
     */
    public static boolean isErrorDispatch(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        return request.getDispatcherType() == DispatcherType.ERROR
                && "/error".equals(RequestPaths.pathWithinApplication(request));
    }
}
