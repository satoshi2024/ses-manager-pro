package com.ses.controller.page;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/** 承認inbox・申請一覧・詳細画面のページ遷移。業務処理はAPI/engineへ委譲する。 */
@Controller
@RequestMapping("/approval")
public class ApprovalPageController {
    @GetMapping({"", "/inbox"})
    public String inbox() {
        return "approval/inbox";
    }

    @GetMapping("/requests")
    public String requests() {
        return "approval/requests";
    }

    @GetMapping("/requests/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("requestId", id);
        return "approval/detail";
    }

    /** route改版・代理設定は管理者APIの専用画面。 */
    @GetMapping("/routes")
    @PreAuthorize("hasRole('管理者')")
    public String routes() {
        return "approval/routes";
    }
}
