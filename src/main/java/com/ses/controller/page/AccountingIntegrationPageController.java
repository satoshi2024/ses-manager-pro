package com.ses.controller.page;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 会計・支払連携 画面コントローラー (A1 / design §6.2)。
 * 管理者およびマネージャー（財務担当）のみアクセス可能。
 */
@Controller
@RequestMapping("/accounting")
public class AccountingIntegrationPageController {

    @GetMapping("/integration")
    @PreAuthorize("hasAnyRole('管理者', 'マネージャー')")
    public String integrationPage(Model model) {
        return "accounting/integration";
    }
}
