package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 要員セルフサービス - マイライフサイクル ページコントローラー
 */
@Controller
@RequestMapping("/my/lifecycle")
public class MyLifecyclePageController {

    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("pageTitle", "マイライフサイクル");
        return "my/lifecycle";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable("id") Long id, Model model) {
        model.addAttribute("pageTitle", "マイライフサイクル詳細");
        model.addAttribute("caseId", id);
        return "my/lifecycle-detail";
    }
}
