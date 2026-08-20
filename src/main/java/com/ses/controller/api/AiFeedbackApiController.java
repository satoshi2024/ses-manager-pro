package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.AiFeedback;
import com.ses.service.ai.AiFeedbackService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiFeedbackApiController {

    private final AiFeedbackService aiFeedbackService;

    @Data
    public static class FeedbackRequest {
        private Long itemId;
        private String decision;
        private String reasonCode;
        private String comment;
    }

    @PostMapping("/feedback")
    public ApiResult<AiFeedback> record(@RequestBody FeedbackRequest request) {
        return ApiResult.success(aiFeedbackService.record(
                request.getItemId(), request.getDecision(), request.getReasonCode(), request.getComment()));
    }
}
