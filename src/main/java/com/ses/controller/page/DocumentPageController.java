package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 文書台帳 画面コントローラー（T024）。
 */
@Controller
@RequestMapping("/document")
public class DocumentPageController {

    @GetMapping("/list")
    public String list() {
        return "document/list";
    }

    @GetMapping("/detail/{id}")
    public String detail(@PathVariable("id") Long id, Model model) {
        model.addAttribute("documentId", id);
        return "document/detail";
    }
}
