package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/compliance")
public class CompliancePageController {

    @GetMapping
    public String index() {
        return "compliance/list";
    }
}
