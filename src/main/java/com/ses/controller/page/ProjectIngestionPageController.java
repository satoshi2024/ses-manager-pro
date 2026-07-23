package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 案件メール取込 画面コントローラー。
 */
@Controller
@RequestMapping("/project-ingestion")
public class ProjectIngestionPageController {

    @GetMapping
    public String list() {
        return "project-ingestion/list";
    }

    @GetMapping("/review/{id}")
    public String review(@PathVariable Long id) {
        return "project-ingestion/review";
    }
}
