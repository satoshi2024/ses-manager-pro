package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/monthly-closing")
public class MonthlyClosingPageController {

    @GetMapping
    public String list() {
        return "monthly-closing/list";
    }
}
