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

    private final com.ses.service.RoleMenuService roleMenuService;

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
        if (!"管理者".equals(role)) {
            java.util.List<String> allowedMenus = roleMenuService.getMenuKeysByRole(role);
            if (!allowedMenus.contains("dashboard")) {
                if (allowedMenus.contains("engineer")) return "redirect:/engineer/list";
                if (allowedMenus.contains("project")) return "redirect:/project/list";
                if (allowedMenus.contains("customer")) return "redirect:/customer/list";
                if (allowedMenus.contains("proposal")) return "redirect:/proposal/kanban";
                if (allowedMenus.contains("contract")) return "redirect:/contract/list";
                if (allowedMenus.contains("user")) return "redirect:/user/list";
            }
        }
        return "redirect:/dashboard";
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
