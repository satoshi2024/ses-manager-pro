package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.ai.MatchResultDto;
import com.ses.dto.ai.SkillSheetDto;
import com.ses.service.ai.AiMatchingService;
import com.ses.service.ai.AiSkillSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI機能APIコントローラー
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiApiController {

    private final AiMatchingService aiMatchingService;
    private final AiSkillSheetService aiSkillSheetService;

    /**
     * エンジニアと案件のマッチングを行う
     */
    @PostMapping("/match/engineer-to-projects")
    public ApiResult<List<MatchResultDto>> matchEngineerToProjects(@RequestBody Map<String, Long> payload) {
        Long engineerId = payload.get("engineerId");
        return ApiResult.success(aiMatchingService.findMatchingProjects(engineerId));
    }

    /**
     * 案件にマッチする要員を検索する（逆方向推薦）
     */
    @GetMapping("/matching/project/{projectId}")
    public ApiResult<List<MatchResultDto>> findMatchingEngineers(@PathVariable Long projectId) {
        return ApiResult.success(aiMatchingService.findMatchingEngineers(projectId));
    }

    /**
     * スキルシートを生成する
     */
    @PostMapping("/skill-sheet/generate")
    public ApiResult<SkillSheetDto> generateSkillSheet(@RequestBody Map<String, Long> payload) {
        Long engineerId = payload.get("engineerId");
        return ApiResult.success(aiSkillSheetService.generateSkillSheet(engineerId));
    }
}
