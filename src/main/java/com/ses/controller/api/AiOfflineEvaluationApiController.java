package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.AiEvaluation;
import com.ses.service.ai.AiOfflineEvaluationService;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * オフライン評価は本番の実行面に公開しない。test/dev profileでのみ登録する。
 */
@RestController
@RequestMapping("/api/ai/evaluations")
@RequiredArgsConstructor
@Profile({"test", "dev"})
public class AiOfflineEvaluationApiController {

    private final AiOfflineEvaluationService offlineEvaluationService;
    private final CopilotFeatureGate featureGate;

    @PostMapping("/run")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<AiEvaluation> run(@Valid @RequestBody RunRequest request) {
        featureGate.assertEvaluationAllowed();
        return ApiResult.success(offlineEvaluationService.evaluate(
                request.candidateVersionId(), request.baselineVersionId()));
    }

    public record RunRequest(@Positive Long candidateVersionId, @Positive Long baselineVersionId) {
    }
}
