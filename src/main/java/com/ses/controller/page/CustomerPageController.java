package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 顧客画面コントローラー
 */
@Controller
@RequestMapping("/customer")
public class CustomerPageController {

    /**
     * 顧客一覧画面
     */
    @GetMapping("/list")
    public String list() {
        return "customer/list";
    }
}
