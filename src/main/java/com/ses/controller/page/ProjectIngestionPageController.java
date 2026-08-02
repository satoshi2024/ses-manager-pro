package com.ses.controller.page;

import com.ses.entity.ProjectIngestion;
import com.ses.service.ProjectIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * 案件メール取込 画面コントローラー。
 */
@Controller
@RequestMapping("/project-ingestion")
@RequiredArgsConstructor
public class ProjectIngestionPageController {

    private final ProjectIngestionService projectIngestionService;

    @GetMapping
    public String list() {
        return "project-ingestion/list";
    }

    @GetMapping("/review/{id}")
    public String review(@PathVariable Long id, Model model) {
        ProjectIngestion job = projectIngestionService.getById(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "error.scope.notFound");
        }
        model.addAttribute("jobId", id);
        return "project-ingestion/review";
    }
}
