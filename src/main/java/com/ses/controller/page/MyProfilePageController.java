package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 要員ポータル（プロフィール・スキル変更申請）ページ。 */
@Controller
@RequestMapping("/my")
public class MyProfilePageController {

    @GetMapping("/profile")
    public String profile() {
        return "my-profile/index";
    }
}
