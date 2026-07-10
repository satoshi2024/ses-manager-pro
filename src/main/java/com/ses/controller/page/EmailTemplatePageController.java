package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * メールテンプレート画面コントローラー
 */
@Controller
@RequestMapping("/email/template")
public class EmailTemplatePageController {

    /**
     * メールテンプレート管理画面
     */
    @GetMapping("/list")
    public String list() {
        return "email-template/list";
    }
}
