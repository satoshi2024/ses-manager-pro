package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import lombok.RequiredArgsConstructor;

/**
 * ログイン・ページ遷移コントローラー
 * ログイン画面、ダッシュボードなどのページ遷移を管理する
 */
@Controller
@RequiredArgsConstructor
public class LoginPageController {

    private final com.ses.service.MenuCacheService menuCacheService;

    /**
     * ログインページを表示する
     *
     * @return ログインテンプレート名
     */
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    /**
     * ルートパスからダッシュボードにリダイレクトする
     *
     * @return 適切なページへのリダイレクト
     */
    @GetMapping("/")
    public String index(org.springframework.security.core.Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return "redirect:/login";
        }
        boolean isEngineer = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_要員".equals(a.getAuthority()));
        if (isEngineer) {
            return "redirect:/my/timesheet";
        }
        String role = auth.getAuthorities().stream()
                .filter(a -> a.getAuthority().startsWith("ROLE_"))
                .map(a -> a.getAuthority().substring(5))
                .findFirst().orElse("");
        if ("管理者".equals(role)) {
            return "redirect:/dashboard";
        }

        java.util.List<String> allowedMenus = menuCacheService.getMenuKeysByRole(role);
        if (allowedMenus.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "アクセス可能なメニューがありません");
        }

        // ダッシュボード権限があれば最優先 (R7-05)
        if (allowedMenus.contains("dashboard")) {
            return "redirect:/dashboard";
        }

        for (com.ses.entity.Menu menu : menuCacheService.getAllMenus()) {
            if (allowedMenus.contains(menu.getMenuKey())) {
                String path = menu.getPathPrefix();
                if (path != null && !path.isEmpty()) {
                    // bare prefix を実際の一覧画面等へ変換する（R7-05）
                    switch (path) {
                        case "/ai": return "redirect:/ai/matching";
                        case "/candidate": return "redirect:/candidate/list";
                        case "/contract": return "redirect:/contract/list";
                        case "/customer": return "redirect:/customer/list";
                        case "/email/template": return "redirect:/email/template/list";
                        case "/engineer": return "redirect:/engineer/list";
                        case "/project": return "redirect:/project/list";
                        case "/proposal": return "redirect:/proposal/kanban";
                        case "/user": return "redirect:/user/list";
                        // 以下は prefix と 着地URL が同一（GetMapping が空または /）
                        case "/analytics":
                        case "/audit-log":
                        case "/contract-document":
                        case "/invoice":
                        case "/monthly-closing":
                        case "/payroll":
                        case "/quotation":
                        case "/sales-performance":
                        case "/system-config":
                        case "/todo":
                        case "/work-record":
                            return "redirect:" + path;
                        default: 
                            return "redirect:" + path;
                    }
                }
            }
        }

        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.FORBIDDEN, "アクセス可能なメニューがありません");
    }

    /**
     * ダッシュボードページを表示する
     *
     * @return ダッシュボードテンプレート名
     */
    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard/index";
    }

    @GetMapping("/dashboard/profit")
    public String dashboardProfit() {
        return "dashboard/profit";
    }
}
