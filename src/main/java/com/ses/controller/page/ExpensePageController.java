package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 経費管理（管理者/マネージャー）ページ。 */
@Controller
@RequestMapping("/expenses")
public class ExpensePageController {

    @GetMapping
    public String index() {
        return "expenses/index";
    }
}
