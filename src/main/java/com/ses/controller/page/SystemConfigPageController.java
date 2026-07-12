package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * システム設定画面コントローラー（管理者専用、SecurityConfigでアクセス制御）。
 */
@Controller
@RequestMapping("/system-config")
public class SystemConfigPageController {

    @GetMapping
    public String index() {
        return "system-config/list";
    }
}
