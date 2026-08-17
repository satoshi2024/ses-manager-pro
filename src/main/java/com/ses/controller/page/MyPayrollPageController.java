package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 要員本人の給与明細ページ（engineer-self-service-portal-v2 A2）。view名のみ返す。
 */
@Controller
@RequestMapping("/my")
public class MyPayrollPageController {

    @GetMapping("/payroll")
    public String payroll() {
        return "my-payroll/index";
    }
}
