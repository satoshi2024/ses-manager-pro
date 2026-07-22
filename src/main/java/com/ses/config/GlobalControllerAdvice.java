package com.ses.config;

import com.ses.service.RoleMenuService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collections;
import java.util.List;

/**
 * RoleMenuServiceはObjectProvider経由で取得する。@ControllerAdviceは
 * @WebMvcTest等のテストスライスでも読み込まれるため、必須依存にすると
 * サービス層Beanが存在しないスライスで無関係なテストまで壊れてしまう。
 */
@Slf4j
@ControllerAdvice(basePackages = "com.ses.controller.page")
@RequiredArgsConstructor
public class GlobalControllerAdvice {

    private final ObjectProvider<RoleMenuService> roleMenuServiceProvider;

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * 言語切替リンクのベースURL(現在URI+クエリ、既存の lang パラメータは除去し
     * 末尾を "lang=" にした文字列)。テンプレート側で言語コードを連結して使う。
     * 例: th:href="${langSwitchBase} + 'en'"
     */
    @ModelAttribute("langSwitchBase")
    public String langSwitchBase(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder(request.getRequestURI());
        String queryString = request.getQueryString();
        sb.append('?');
        boolean hasParam = false;
        if (queryString != null && !queryString.isEmpty()) {
            for (String pair : queryString.split("&")) {
                if (pair.isEmpty() || pair.startsWith("lang=") || pair.equals("lang")) {
                    continue;
                }
                if (hasParam) {
                    sb.append('&');
                }
                sb.append(pair);
                hasParam = true;
            }
        }
        if (hasParam) {
            sb.append('&');
        }
        sb.append("lang=");
        return sb.toString();
    }

    /**
     * ログイン中ユーザーのロールがアクセス可能なメニューキー一覧
     * サイドバーのメニュー表示可否の判定に使用する
     */
    @ModelAttribute("allowedMenus")
    public List<String> allowedMenus(Authentication authentication) {
        RoleMenuService roleMenuService = roleMenuServiceProvider.getIfAvailable();
        if (authentication == null || roleMenuService == null) {
            return Collections.emptyList();
        }
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .findFirst()
                .map(authority -> authority.substring("ROLE_".length()))
                .orElse(null);
        if (role == null) {
            return Collections.emptyList();
        }
        try {
            if ("管理者".equals(role)) {
                return roleMenuService.getAllMenuKeys();
            }
            return roleMenuService.getMenuKeysByRole(role);
        } catch (Exception e) {
            log.warn("メニュー権限の取得に失敗しました（role={}）", role, e);
            return Collections.emptyList();
        }
    }
}
