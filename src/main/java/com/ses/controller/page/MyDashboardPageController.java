package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 要員ポータル（マイダッシュボード）ページ。 */
@Controller
@RequestMapping("/my")
public class MyDashboardPageController {

    @GetMapping("/dashboard")
    public String dashboard() {
        return "my-dashboard/index";
    }
}
