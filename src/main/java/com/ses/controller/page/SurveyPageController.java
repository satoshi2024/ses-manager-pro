package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** サーベイ管理ページ（HR/管理者/マネージャー。design §6.2）。 */
@Controller
@RequestMapping("/surveys")
public class SurveyPageController {

    @GetMapping
    public String index() {
        return "surveys/index";
    }
}