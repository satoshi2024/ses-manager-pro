package com.ses.controller.page;

import com.ses.service.BpAvailabilityIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * 外部要員在庫メール取込 画面コントローラー。
 */
@Controller
@RequestMapping("/bp-availability-ingestion")
@RequiredArgsConstructor
public class BpAvailabilityIngestionPageController {

    private final BpAvailabilityIngestionService ingestionService;

    @GetMapping
    public String list() {
        return "bp-availability-ingestion/list";
    }

    @GetMapping("/review/{jobId}")
    public String review(@PathVariable Long jobId, Model model) {
        if (ingestionService.getById(jobId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "取込ジョブが見つかりません");
        }
        model.addAttribute("jobId", jobId);
        return "bp-availability-ingestion/review";
    }
}
