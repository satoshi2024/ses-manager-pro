package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.ai.MatchResultDto;
import com.ses.service.ai.AiMatchingService;
import com.ses.service.ai.AiRecommendationRecorder;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI機能APIコントローラー（マッチング系）。
 * 対話系は AiRestController、取込解析系は ResumeIngestionApiController が担当する。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiApiController {

    private final AiMatchingService aiMatchingService;
    private final DataScopeService dataScopeService;
    private final ObjectProvider<AiRecommendationRecorder> recommendationRecorder;

    @PostMapping("/match/engineer-to-projects")
    public ApiResult<List<MatchResultDto>> matchEngineerToProjects(@RequestBody java.util.Map<String, Long> payload) {
        Long engineerId = payload.get("engineerId");
        if (engineerId != null) {
            dataScopeService.assertAllowedEngineer(engineerId);
        }
        List<MatchResultDto> results = aiMatchingService.findMatchingProjects(engineerId);
        record(results);
        return ApiResult.success(results);
    }

    @GetMapping("/matching/project/{projectId}")
    public ApiResult<List<MatchResultDto>> findMatchingEngineers(@PathVariable Long projectId) {
        if (projectId != null) {
            dataScopeService.assertAllowedProject(projectId);
        }
        List<MatchResultDto> results = aiMatchingService.findMatchingEngineers(projectId);
        record(results);
        return ApiResult.success(results);
    }

    private void record(List<MatchResultDto> results) {
        AiRecommendationRecorder recorder = recommendationRecorder.getIfAvailable();
        if (recorder != null) {
            recorder.recordMatch("MATCHING", null, results);
        }
    }
}
