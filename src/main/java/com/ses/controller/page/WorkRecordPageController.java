package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/work-record")
public class WorkRecordPageController {

    @GetMapping
    public String list(Model model) {
        model.addAttribute("title", "勤怠・実績");
        return "work-record/list";
    }
}
