package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 変更申請管理ページ（HR/管理者全件、マネージャー配下。design §6.2）。 */
@Controller
@RequestMapping("/engineer-change-requests")
public class EngineerChangeRequestPageController {

    @GetMapping
    public String index() {
        return "engineer-change-requests/index";
    }
}
