package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * スキルシート取込画面コントローラー
 */
@Controller
@RequestMapping("/resume-ingestion")
public class ResumeIngestionPageController {

    /**
     * スキルシート取込一覧画面
     */
    @GetMapping
    public String list() {
        return "resume-ingestion/list";
    }

    /**
     * スキルシートレビュー画面
     */
    @GetMapping("/review/{id}")
    public String review(@PathVariable Long id) {
        return "resume-ingestion/review";
    }
}
