package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 月次検収ページ（order-acceptance-workflow / B1）。 */
@Controller
@RequestMapping("/acceptance")
public class AcceptancePageController {

    @GetMapping
    public String list() {
        return "acceptance/list";
    }
}
