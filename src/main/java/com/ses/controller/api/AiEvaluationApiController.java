package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.ai.AiEvaluationDashboardDto;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiEvaluation;
import com.ses.service.ai.AiArtifactVersionService;
import com.ses.service.ai.AiEvaluationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/evaluations")
@RequiredArgsConstructor
@Profile({"test", "dev"})
@PreAuthorize("hasAnyRole('管理者','マネージャー','営業')")
public class AiEvaluationApiController {

    private final AiEvaluationQueryService queryService;
    private final AiArtifactVersionService artifactVersionService;

    @GetMapping
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<List<AiEvaluation>> list() {
        return ApiResult.success(queryService.listEvaluations());
    }

    @GetMapping("/dashboard")
    public ApiResult<AiEvaluationDashboardDto> dashboard() {
        return ApiResult.success(queryService.dashboard());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<AiArtifactVersion> approve(@PathVariable Long id) {
        return ApiResult.success(artifactVersionService.promoteApproved(id));
    }

    @PostMapping("/rollback/{versionId}")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<AiArtifactVersion> rollback(@PathVariable Long versionId) {
        return ApiResult.success(artifactVersionService.rollbackTo(versionId));
    }

}
