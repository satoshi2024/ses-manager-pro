package com.ses.controller.page;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/inbound-invoice")
public class DigitalInvoiceReviewPageController {

    @GetMapping
    @PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー')")
    public String index() {
        return "invoice/inbound";
    }
}
