package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 要員ポータル（サーベイ）ページ。B2で拡充する。 */
@Controller
@RequestMapping("/my")
public class MySurveyPageController {

    @GetMapping("/surveys")
    public String index() {
        return "my-surveys/index";
    }
}