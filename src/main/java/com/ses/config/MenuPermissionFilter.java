package com.ses.config;

import com.ses.entity.Menu;
import com.ses.mapper.MenuMapper;
import com.ses.service.RoleMenuService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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

    private final ObjectProvider<MenuMapper> menuMapperProvider;
    private final ObjectProvider<RoleMenuService> roleMenuServiceProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String uri = request.getRequestURI();

        MenuMapper menuMapper = menuMapperProvider.getIfAvailable();
        RoleMenuService roleMenuService = roleMenuServiceProvider.getIfAvailable();

        if (authentication == null || !authentication.isAuthenticated() || !isMenuControlledPath(uri)
                || menuMapper == null || roleMenuService == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String role = currentRole(authentication);
        // 管理者は全メニューにアクセス可能。メニュー権限設定で誤って自ロールの
        // メニューを外しても管理画面から締め出されないよう、必ず素通しする。
        if (ADMIN_ROLE.equals(role)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<Menu> matchedMenu;
        List<String> allowedMenuKeys;
        try {
            // 画面プレフィックス・APIプレフィックスのどちらかに前方一致するメニューのうち、
            // 一致した長さが最長のもの（最も具体的なもの）を対象メニューとする
            matchedMenu = menuMapper.selectList(null).stream()
                    .filter(menu -> matchedPrefixLength(menu, uri) > 0)
                    .max((a, b) -> Integer.compare(matchedPrefixLength(a, uri), matchedPrefixLength(b, uri)));

            if (matchedMenu.isEmpty()) {
                filterChain.doFilter(request, response);
                return;
            }

            allowedMenuKeys = role == null ? List.of() : roleMenuService.getMenuKeysByRole(role);
        } catch (Exception e) {
            log.warn("メニュー権限の判定に失敗しました。アクセスを許可します: uri={}", uri, e);
            filterChain.doFilter(request, response);
            return;
        }

        if (allowedMenuKeys.contains(matchedMenu.get().getMenuKey())) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        if (uri.startsWith("/api/")) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"このメニューへのアクセス権限がありません\",\"data\":null}");
        }
    }

    private boolean isMenuControlledPath(String uri) {
        return !uri.equals("/logout") && !uri.equals("/") && (uri.startsWith("/api/") || !uri.contains("."));
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
