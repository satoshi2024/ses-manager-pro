package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.survey.SurveyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 要員ポータル（サーベイ）API。本人scopeはengineer-account linkから解決（design §3）。
 */
@RestController
@RequestMapping("/api/my/surveys")
@RequiredArgsConstructor
public class MySurveyApiController {

    private final EngineerAccountLinkService linkService;
    private final SurveyService surveyService;

    private Long currentEngineerId() {
        Long engineerId = linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
        if (engineerId == null) {
            throw BusinessException.of(403, "error.my.notLinked");
        }
        return engineerId;
    }

    @GetMapping
    public ApiResult<List<SurveyService.CampaignDto>> campaigns() {
        return ApiResult.success(surveyService.myActiveCampaigns(currentEngineerId()));
    }

    @GetMapping("/{campaignId}")
    public ApiResult<SurveyService.MyCampaignDetail> detail(@PathVariable Long campaignId) {
        return ApiResult.success(surveyService.myCampaignDetail(currentEngineerId(), campaignId));
    }

    @PostMapping("/{campaignId}/answers")
    public ApiResult<Void> submitAnswers(@PathVariable Long campaignId, @RequestBody SubmitRequest request) {
        List<SurveyService.AnswerInput> answers = request == null ? List.of() : request.getAnswers();
        boolean consent = request != null && request.isConsent();
        surveyService.submitAnswers(currentEngineerId(), campaignId, consent, answers);
        return ApiResult.success(null);
    }

    public static class SubmitRequest {
        private boolean consent;
        private List<SurveyService.AnswerInput> answers;

        public boolean isConsent() {
            return consent;
        }

        public void setConsent(boolean consent) {
            this.consent = consent;
        }

        public List<SurveyService.AnswerInput> getAnswers() {
            return answers;
        }

        public void setAnswers(List<SurveyService.AnswerInput> answers) {
            this.answers = answers;
        }
    }
}
