package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/sales-performance")
public class SalesPerformancePageController {

    @GetMapping
    public String index() {
        return "sales-performance/list";
    }
}
