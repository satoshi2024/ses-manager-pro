package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.config.AiConfig;
import com.ses.dto.ai.MatchResultDto;
import com.ses.service.ai.AiMatchingService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
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
    private final AiConfig aiConfig;

    private void checkAiEnabled() {
        if (!aiConfig.isEnabled()) {
            throw BusinessException.of("error.ai.disabled");
        }
    }

    /**
     * エンジニアと案件のマッチングを行う
     */
    @PostMapping("/match/engineer-to-projects")
    public ApiResult<List<MatchResultDto>> matchEngineerToProjects(@RequestBody java.util.Map<String, Long> payload) {
        Long engineerId = payload.get("engineerId");
        if (engineerId != null) {
            dataScopeService.assertAllowedEngineer(engineerId);
        }
        return ApiResult.success(aiMatchingService.findMatchingProjects(engineerId));
    }

    /**
     * 案件にマッチする要員を検索する（逆方向推薦）
     */
    @GetMapping("/matching/project/{projectId}")
    public ApiResult<List<MatchResultDto>> findMatchingEngineers(@PathVariable Long projectId) {
        if (projectId != null) {
            dataScopeService.assertAllowedProject(projectId);
        }
        return ApiResult.success(aiMatchingService.findMatchingEngineers(projectId));
    }
}
