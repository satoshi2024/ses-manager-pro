package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.service.survey.SurveyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * サーベイ管理API（HR/管理者=全件、マネージャー=組織scope配下の集計。design §6.2）。
 * 匿名閾値（survey.min-answers）未満のsegmentは非表示（design §5）。
 */
@RestController
@RequestMapping("/api/surveys")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者','HR','マネージャー')")
public class SurveyApiController {

    private final SurveyService surveyService;

    // ---- template ----
    @GetMapping("/templates")
    public ApiResult<Page<SurveyService.TemplateDto>> templates(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResult.success(surveyService.pageTemplates(current, size));
    }

    @PostMapping("/templates")
    public ApiResult<SurveyService.TemplateDto> createTemplate(@RequestBody TemplateRequest request) {
        return ApiResult.success(surveyService.createTemplate(request.getTemplateKey(), request.getTitle(),
                request.getDescription(), request.getQuestions()));
    }

    @PostMapping("/templates/{id}")
    public ApiResult<SurveyService.TemplateDto> updateTemplate(@PathVariable Long id,
                                                               @RequestBody TemplateRequest request) {
        return ApiResult.success(surveyService.updateTemplate(id, request.getTitle(),
                request.getDescription(), request.getQuestions()));
    }

    // ---- campaign ----
    @GetMapping
    public ApiResult<Page<SurveyService.CampaignDto>> campaigns(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResult.success(surveyService.pageCampaigns(current, size));
    }

    @PostMapping
    public ApiResult<SurveyService.CampaignDto> createCampaign(@RequestBody CampaignRequest request) {
        return ApiResult.success(surveyService.createCampaign(request.getTemplateId(), request.getTitle(),
                request.getPeriodFrom(), request.getPeriodTo()));
    }

    @PostMapping("/{id}/activate")
    public ApiResult<SurveyService.CampaignDto> activate(@PathVariable Long id) {
        return ApiResult.success(surveyService.activateCampaign(id));
    }

    @PostMapping("/{id}/close")
    public ApiResult<SurveyService.CampaignDto> close(@PathVariable Long id) {
        return ApiResult.success(surveyService.closeCampaign(id));
    }

    // ---- 集計 ----
    @GetMapping("/{id}/aggregate")
    public ApiResult<SurveyService.AggregateResult> aggregate(@PathVariable Long id) {
        return ApiResult.success(surveyService.aggregate(id));
    }

    /** 個別回答（HR/管理者のみ。confidentialコメントもHR/管理者のみ可視）。 */
    @GetMapping("/{id}/responses")
    public ApiResult<List<SurveyService.ResponseView>> responses(@PathVariable Long id) {
        return ApiResult.success(surveyService.responses(id));
    }

    public static class TemplateRequest {
        private String templateKey;
        private String title;
        private String description;
        private List<SurveyService.QuestionDef> questions;

        public String getTemplateKey() {
            return templateKey;
        }

        public void setTemplateKey(String templateKey) {
            this.templateKey = templateKey;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<SurveyService.QuestionDef> getQuestions() {
            return questions;
        }

        public void setQuestions(List<SurveyService.QuestionDef> questions) {
            this.questions = questions;
        }
    }

    public static class CampaignRequest {
        private Long templateId;
        private String title;
        private LocalDate periodFrom;
        private LocalDate periodTo;

        public Long getTemplateId() {
            return templateId;
        }

        public void setTemplateId(Long templateId) {
            this.templateId = templateId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public LocalDate getPeriodFrom() {
            return periodFrom;
        }

        public void setPeriodFrom(LocalDate periodFrom) {
            this.periodFrom = periodFrom;
        }

        public LocalDate getPeriodTo() {
            return periodTo;
        }

        public void setPeriodTo(LocalDate periodTo) {
            this.periodTo = periodTo;
        }
    }
}
