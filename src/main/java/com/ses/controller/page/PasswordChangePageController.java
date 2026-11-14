package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 初回ログイン時のパスワード変更画面（配備決定-3）。
 *
 * <p>{@code PasswordChangeEnforcementFilter} が変更前の画面遷移をすべてここへredirectする
 * （pathは {@code PasswordChangeEnforcementFilter.CHANGE_PAGE} と同じ）。sidebarや通知を持つ共通layoutは
 * 使わない（変更前は遮断されて表示できないため）。変更は既存の {@code PUT /api/profile/password} で行う。
 */
@Controller
public class PasswordChangePageController {

    @GetMapping("/password/change")
    public String change() {
        return "password/change";
    }
}
