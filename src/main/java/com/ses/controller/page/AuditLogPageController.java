package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 監査ログ画面コントローラー（管理者専用、SecurityConfigでアクセス制御）。
 */
@Controller
@RequestMapping("/audit-log")
public class AuditLogPageController {

    @GetMapping
    public String index() {
        return "audit-log/list";
    }
}
