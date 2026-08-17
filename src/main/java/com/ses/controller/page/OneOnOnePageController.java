package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 1on1管理ページ（HR/管理者/マネージャー/営業。design §6.2）。 */
@Controller
@RequestMapping("/one-on-ones")
public class OneOnOnePageController {

    @GetMapping
    public String index() {
        return "one-on-ones/index";
    }
}