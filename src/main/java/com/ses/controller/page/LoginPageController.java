package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * ログイン・ページ遷移コントローラー
 * ログイン画面、ダッシュボードなどのページ遷移を管理する
 */
@Controller
public class LoginPageController {

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
     * @return ダッシュボードへのリダイレクト
     */
    @GetMapping("/")
    public String index() {
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
}
