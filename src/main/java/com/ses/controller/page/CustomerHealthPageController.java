package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 顧客ヘルススコア・CSダッシュボード画面コントローラ
 */
@Controller
@RequestMapping({"/customer-success/health", "/service-desk/health"})
public class CustomerHealthPageController {

    @GetMapping
    public String healthDashboard() {
        return "customer-success/health";
    }
}
