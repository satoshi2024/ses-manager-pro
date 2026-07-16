package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/contract-document")
public class ContractDocumentPageController {
    @GetMapping
    public String list() {
        return "contract-document/list";
    }
}
