package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/quotation")
public class QuotationPageController {

    @GetMapping
    public String list() {
        return "quotation/list";
    }
}
