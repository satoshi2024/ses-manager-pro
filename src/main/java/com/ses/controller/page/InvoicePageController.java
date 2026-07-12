package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/invoice")
public class InvoicePageController {

    @GetMapping
    public String list() {
        return "invoice/list";
    }

    @GetMapping("/{id}/print")
    public String print(@PathVariable Long id, Model model) {
        model.addAttribute("invoiceId", id);
        return "invoice/print";
    }
}
