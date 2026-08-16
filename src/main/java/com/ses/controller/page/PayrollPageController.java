package com.ses.controller.page;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 給与画面。HFP-01-R08-3: 給与pageはキャッシュ禁止（no-store）。
 */
@Controller
@RequestMapping("/payroll")
public class PayrollPageController {
    @GetMapping
    public String index(HttpServletResponse response) {
        noStore(response);
        return "payroll/index";
    }

    static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
    }
}
