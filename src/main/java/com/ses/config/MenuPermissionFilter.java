package com.ses.config;

import com.ses.entity.Menu;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.ActionPermissionResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * ロール別メニュー権限フィルター
 * ログイン中ユーザーのロールがアクセス許可されていないメニューのURLへの
 * 直接アクセスを遮断する（サイドバー非表示だけでは防げないため）
 *
 * 依存Beanは ObjectProvider 経由で取得する。テストスライス（@WebMvcTest等）では
 * MenuMapper/RoleMenuService がコンテキストに存在しないため、必須依存にすると
 * フィルターBean自体の生成に失敗し、対象外のテストまで巻き込んで壊れてしまう。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuPermissionFilter extends OncePerRequestFilter {

    /** 全メニューにアクセス可能な特権ロール（メニュー権限設定によらず遮断しない） */
    private static final String ADMIN_ROLE = "管理者";

    private final ObjectProvider<com.ses.service.MenuCacheService> menuCacheServiceProvider;
    private final ObjectProvider<AuthorizationService> authorizationServiceProvider;
    private final ObjectProvider<com.ses.service.AuditLogService> auditLogServiceProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String uri = request.getRequestURI();

        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated() || !isMenuControlledPath(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Portalは専用SecurityFilterChainと権限modelが入るまで既存内部roleへ流さない。
        if (uri.equals("/portal") || uri.startsWith("/portal/")
                || uri.equals("/api/portal") || uri.startsWith("/api/portal/")) {
            deny(request, response);
            return;
        }

        AuthorizationService authorizationService = authorizationServiceProvider.getIfAvailable();
        String actionKey = ActionPermissionResolver.resolve(request.getMethod(), uri);
        if (uri.startsWith("/api/") && actionKey == null && !isAuthenticationInfrastructure(uri)) {
            deny(request, response);
            return;
        }

        com.ses.service.MenuCacheService menuCacheService = menuCacheServiceProvider.getIfAvailable();
        if (menuCacheService == null) {
            if (!authorize(authentication, actionKey, authorizationService, request, response)) {
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        String role = currentRole(authentication);
        Optional<Menu> matchedMenu;
        List<String> allowedMenuKeys;
        try {
            // 画面プレフィックス・APIプレフィックスのどちらかに前方一致するメニューのうち、
            // 一致した長さが最長のもの（最も具体的なもの）を対象メニューとする
            matchedMenu = menuCacheService.getAllMenus().stream()
                    .filter(menu -> matchedPrefixLength(menu, uri) > 0)
                    .max((a, b) -> Integer.compare(matchedPrefixLength(a, uri), matchedPrefixLength(b, uri)));

            allowedMenuKeys = role == null ? List.of() : menuCacheService.getMenuKeysByRole(role);
        } catch (Exception e) {
            log.warn("メニュー権限の判定に失敗したためアクセスを拒否します: uri={}", uri, e);
            deny(request, response);
            return;
        }

        // page直達もmatched menuのAPI prefixから同じview actionを導出する。
        if (actionKey == null && matchedMenu.isPresent() && !uri.startsWith("/api/")) {
            actionKey = ActionPermissionResolver.resolve("GET", matchedMenu.get().getApiPrefix());
            if (actionKey == null) {
                deny(request, response);
                return;
            }
        }
        if (actionKey != null) {
            request.setAttribute(ActionPermissionResolver.REQUEST_ACTION_ATTRIBUTE, actionKey);
        }
        if (!authorize(authentication, actionKey, authorizationService, request, response)) {
            return;
        }

        // 管理者は既知actionに限りmenu設定による自己lockoutを受けない。
        if (ADMIN_ROLE.equals(role)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (matchedMenu.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (allowedMenuKeys.contains(matchedMenu.get().getMenuKey())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (uri.startsWith("/api/")) {
            deny(request, response);
        } else {
            // 画面遷移はエラーディスパッチ経由で統一エラーページ(CustomErrorController)を描画する
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    private void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
        com.ses.service.AuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        boolean breakGlass = session != null
                && session.getAttribute(com.ses.service.security.BreakGlassService.INCIDENT_ID_ATTRIBUTE) != null;
        if (breakGlass) {
            if (auditLogService == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
            try {
                auditLogService.recordRequired(com.ses.common.util.SecurityUtils.currentUsername(),
                        request.getMethod(), request.getRequestURI(), HttpServletResponse.SC_FORBIDDEN,
                        "BREAK_GLASS_PERMISSION_DENIED", false);
            } catch (RuntimeException e) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        if (auditLogService != null) {
            auditLogService.record(com.ses.common.util.SecurityUtils.currentUsername(), request.getMethod(),
                    request.getRequestURI(), HttpServletResponse.SC_FORBIDDEN,
                    "PERMISSION_DENIED", false);
        }
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"このactionへのアクセス権限がありません\",\"data\":null}");
        } else {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    private boolean isMenuControlledPath(String uri) {
        if (uri.startsWith("/api/notifications") && !uri.startsWith("/api/notifications/generate")) {
            return false;
        }
        return !uri.equals("/logout") && !uri.equals("/") && (uri.startsWith("/api/") || !uri.contains("."));
    }

    private boolean isAuthenticationInfrastructure(String uri) {
        return uri.equals("/api/security") || uri.startsWith("/api/security/")
                || uri.equals("/api/auth") || uri.startsWith("/api/auth/");
    }

    private boolean authorize(Authentication authentication, String actionKey,
                              AuthorizationService authorizationService,
                              HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (actionKey == null || authorizationService == null) {
            return true;
        }
        try {
            if (authorizationService.isAllowed(authentication, actionKey)) {
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("action権限の判定に失敗したためアクセスを拒否します: uri={}, action={}",
                    request.getRequestURI(), actionKey, e);
        }
        deny(request, response);
        return false;
    }

    /**
     * URIがメニューの画面プレフィックスまたはAPIプレフィックスに前方一致する場合、
     * 一致したプレフィックスの長さを返す（一致しなければ0）。
     * 一致長が長いほど具体的なメニューとみなす。
     */
    private int matchedPrefixLength(Menu menu, String uri) {
        int length = 0;
        String pathPrefix = menu.getPathPrefix();
        if (pathPrefix != null && !pathPrefix.isEmpty() && uri.startsWith(pathPrefix)) {
            length = pathPrefix.length();
        }
        String apiPrefix = menu.getApiPrefix();
        if (apiPrefix != null && !apiPrefix.isEmpty() && uri.startsWith(apiPrefix)) {
            length = Math.max(length, apiPrefix.length());
        }
        return length;
    }

    private String currentRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse(null);
    }
}
