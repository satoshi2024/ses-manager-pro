package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * サービスデスク画面コントローラ (/service-desk)
 */
@Controller
@RequestMapping("/service-desk")
public class ServiceRequestPageController {

    @GetMapping("/requests")
    public String list(Model model) {
        model.addAttribute("activeMenu", "service-desk");
        return "service-desk/list";
    }

    @GetMapping("/requests/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("activeMenu", "service-desk");
        model.addAttribute("requestId", id);
        return "service-desk/detail";
    }
}
