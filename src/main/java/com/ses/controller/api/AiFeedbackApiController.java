package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.AiFeedback;
import com.ses.service.ai.AiFeedbackService;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.service.ai.copilot.CopilotExecutionContextFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者','マネージャー','営業')")
public class AiFeedbackApiController {

    private final AiFeedbackService aiFeedbackService;
    private final CopilotFeatureGate featureGate;
    private final CopilotExecutionContextFactory contextFactory;

    @Data
    public static class FeedbackRequest {
        private Long itemId;
        private Long runId;
        private String decision;
        private String reasonCode;
        private String comment;
    }

    @PostMapping("/feedback")
    public ApiResult<AiFeedback> record(@RequestBody FeedbackRequest request) {
        // 推薦記録もmanagement flag OFF時は公開しない。記録経路からproviderは呼ばれない。
        featureGate.assertQueryAllowed();
        return ApiResult.success(aiFeedbackService.record(
                new AiFeedbackService.FeedbackCommand(request.getItemId(), request.getRunId(),
                        request.getDecision(), request.getReasonCode(), request.getComment()),
                contextFactory.create()));
    }
}
