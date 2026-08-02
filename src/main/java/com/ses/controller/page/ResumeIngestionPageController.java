package com.ses.controller.page;

import com.ses.entity.ResumeIngestion;
import com.ses.service.ResumeIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * スキルシート取込画面コントローラー
 */
@Controller
@RequestMapping("/resume-ingestion")
@RequiredArgsConstructor
public class ResumeIngestionPageController {

    private final ResumeIngestionService resumeIngestionService;

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
    public String review(@PathVariable Long id, Model model) {
        ResumeIngestion job = resumeIngestionService.getById(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "error.scope.notFound");
        }
        model.addAttribute("jobId", id);
        return "resume-ingestion/review";
    }
}
