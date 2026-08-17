package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 要員ポータル（1on1）ページ。B2で拡充する。 */
@Controller
@RequestMapping("/my")
public class MyOneOnOnePageController {

    @GetMapping("/one-on-ones")
    public String index() {
        return "my-one-on-ones/index";
    }
}