package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 要員ポータル（経費申請）ページ。 */
@Controller
@RequestMapping("/my")
public class MyExpensePageController {

    @GetMapping("/expenses")
    public String expenses() {
        return "my-expenses/index";
    }
}
