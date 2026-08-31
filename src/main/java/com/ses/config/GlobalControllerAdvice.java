package com.ses.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collections;
import java.util.Comparator;
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

    private static final List<NavRoute> NAV_ROUTES = List.of(
            new NavRoute("/dashboard", "/dashboard"),
            new NavRoute("/my/attendance", "/my/attendance"),
            new NavRoute("/my/timesheet", "/my/timesheet"),
            new NavRoute("/my/leave", "/my/leave"),
            new NavRoute("/my/dashboard", "/my/dashboard"),
            new NavRoute("/my/profile", "/my/profile"),
            new NavRoute("/my/payroll", "/my/payroll"),
            new NavRoute("/my/expenses", "/my/expenses"),
            new NavRoute("/my/certification-learning-skill-gap", "/my/certification-learning-skill-gap"),
            new NavRoute("/my/one-on-ones", "/my/one-on-ones"),
            new NavRoute("/my/surveys", "/my/surveys"),
            new NavRoute("/my/lifecycle", "/my/lifecycle"),
            new NavRoute("/engineer-change-requests", "/engineer-change-requests"),
            new NavRoute("/bp-availability-ingestion", "/bp-availability-ingestion"),
            new NavRoute("/project-ingestion", "/project-ingestion"),
            new NavRoute("/contract-document", "/contract-document"),
            new NavRoute("/compliance-gate", "/compliance-gate"),
            new NavRoute("/portal-admin", "/portal-admin"),
            new NavRoute("/work-record/attendance", "/work-record/attendance"),
            new NavRoute("/approval/routes", "/approval/routes"),
            new NavRoute("/crm/leads", "/crm/leads"),
            new NavRoute("/crm/opportunities", "/crm/opportunities"),
            new NavRoute("/email/template", "/email/template"),
            new NavRoute("/accounting/integration", "/accounting/integration"),
            new NavRoute("/ai/evaluation", "/ai/evaluation"),
            new NavRoute("/ai/matching", "/ai/matching"),
            new NavRoute("/engineer", "/engineer"),
            new NavRoute("/lifecycle", "/lifecycle"),
            new NavRoute("/certification-learning-skill-gap", "/certification-learning-skill-gap"),
            new NavRoute("/customer", "/customer"),
            new NavRoute("/project", "/project"),
            new NavRoute("/service-desk/requests", "/service-desk/requests"),
            new NavRoute("/service-desk", "/service-desk"),
            new NavRoute("/customer-success/health", "/customer-success/health"),
            new NavRoute("/customer-success", "/customer-success"),
            new NavRoute("/candidate", "/candidate"),
            new NavRoute("/resume-ingestion", "/resume-ingestion"),
            new NavRoute("/bp-availability", "/bp-availability"),
            new NavRoute("/bp-company", "/bp-company"),
            new NavRoute("/proposal", "/proposal"),
            new NavRoute("/quotation", "/quotation"),
            new NavRoute("/sales-order", "/sales-order"),
            new NavRoute("/acceptance", "/acceptance"),
            new NavRoute("/contract", "/contract"),
            new NavRoute("/document", "/document"),
            new NavRoute("/work-record", "/work-record"),
            new NavRoute("/leave", "/leave"),
            new NavRoute("/approval", "/approval"),
            new NavRoute("/expenses", "/expenses"),
            new NavRoute("/one-on-ones", "/one-on-ones"),
            new NavRoute("/surveys", "/surveys"),
            new NavRoute("/invoice", "/invoice"),
            new NavRoute("/reconciliation", "/reconciliation"),
            new NavRoute("/payroll", "/payroll"),
            new NavRoute("/analytics", "/analytics"),
            new NavRoute("/sales-performance", "/sales-performance"),
            new NavRoute("/management-reports", "/management-reports"),
            new NavRoute("/monthly-closing", "/monthly-closing"),
            new NavRoute("/compliance", "/compliance"),
            new NavRoute("/todo", "/todo"),
            new NavRoute("/ai", "/ai/matching"),
            new NavRoute("/organization", "/organization"),
            new NavRoute("/management-accounting", "/management-accounting"),
            new NavRoute("/user", "/user"),
            new NavRoute("/system-config", "/system-config"),
            new NavRoute("/audit-log", "/audit-log")
    );

    private final ObjectProvider<com.ses.service.MenuCacheService> menuCacheServiceProvider;
    private final ObjectProvider<com.ses.service.security.AuthorizationService> authorizationServiceProvider;

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute("currentNavKey")
    public String currentNavKey(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank() || "/".equals(uri)) {
            return "/";
        }
        return NAV_ROUTES.stream()
                .filter(route -> isPathPrefix(uri, route.pathPrefix()))
                .max(Comparator.comparingInt(route -> route.pathPrefix().length()))
                .map(NavRoute::navKey)
                .orElse("/");
    }

    private static boolean isPathPrefix(String uri, String pathPrefix) {
        return uri.equals(pathPrefix) || uri.startsWith(pathPrefix + "/");
    }

    private record NavRoute(String pathPrefix, String navKey) {
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
        com.ses.service.MenuCacheService menuCacheService = menuCacheServiceProvider.getIfAvailable();
        if (authentication == null || menuCacheService == null) {
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
                return menuCacheService.getAllMenuKeys();
            }
            List<String> roleMenuKeys = menuCacheService.getMenuKeysByRole(role);
            com.ses.service.security.AuthorizationService authorizationService =
                    authorizationServiceProvider.getIfAvailable();
            if (authorizationService == null) {
                return roleMenuKeys;
            }
            return menuCacheService.getAllMenus().stream()
                    .filter(menu -> roleMenuKeys.contains(menu.getMenuKey()))
                    .filter(menu -> {
                        String apiPrefix = menu.getApiPrefix();
                        if (apiPrefix == null || apiPrefix.isBlank()) {
                            apiPrefix = "/api/" + menu.getMenuKey();
                        }
                        String action = com.ses.service.security.ActionPermissionResolver.resolve("GET", apiPrefix);
                        return action != null && authorizationService.isAllowed(authentication, action);
                    })
                    .map(com.ses.entity.Menu::getMenuKey)
                    .toList();
        } catch (Exception e) {
            log.warn("メニュー権限の取得に失敗しました（role={}）", role, e);
            return Collections.emptyList();
        }
    }

    /**
     * サイドバーに表示するロール名。
     *
     * <p>テンプレートから {@code principal.authorities} を直接出すと "[ROLE_管理者]" のような
     * 内部表現がそのまま画面に出てしまう。かといって principal の具象型(LoginUser)に依存すると、
     * principal が素の UserDetails であるテストスライス等で テンプレート評価が落ちる。
     * ここで権限名から接頭辞を外した表示用の文字列を用意し、テンプレートは型に依存しないようにする。
     */
    @ModelAttribute("currentRoleName")
    public String currentRoleName(Authentication authentication) {
        if (authentication == null) {
            return "";
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst()
                .orElse("");
    }
}
