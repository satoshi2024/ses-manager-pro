package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.GeminiService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiRestController {

    private final GeminiService geminiService;
    private final EngineerService engineerService;
    private final ProjectService projectService;

    @Data
    public static class AiChatRequest {
        private String apiKey;
        private String prompt;
        private Long engineerId;
        private Long projectId;
    }

    @PostMapping("/chat")
    public ApiResult<String> chat(@RequestBody AiChatRequest request) {
        try {
            StringBuilder finalPrompt = new StringBuilder();

            // コンテキストの追加
            if (request.getEngineerId() != null) {
                Engineer eng = engineerService.getById(request.getEngineerId());
                if (eng != null) {
                    finalPrompt.append("【要員情報】\n")
                               .append("- 氏名(ｲﾆｼｬﾙ): ").append(eng.getInitialName() != null ? eng.getInitialName() : eng.getFullName()).append("\n")
                               .append("- 経験年数: ").append(eng.getExperienceYears()).append("年\n")
                               .append("- 希望単価: ").append(eng.getExpectedUnitPrice()).append("万円\n")
                               .append("- スキル概要: ").append(eng.getResumeSummary() != null ? eng.getResumeSummary() : "なし").append("\n\n");
                }
            }

            if (request.getProjectId() != null) {
                Project proj = projectService.getById(request.getProjectId());
                if (proj != null) {
                    finalPrompt.append("【案件情報】\n")
                               .append("- 案件名: ").append(proj.getProjectName()).append("\n")
                               .append("- 単価幅: ").append(proj.getUnitPriceMin()).append("〜").append(proj.getUnitPriceMax()).append("万円\n")
                               .append("- リモート: ").append(proj.getRemoteType()).append("\n")
                               .append("- 案件詳細: ").append(proj.getDescription() != null ? proj.getDescription() : "なし").append("\n\n");
                }
            }

            finalPrompt.append("【ユーザーからの指示】\n").append(request.getPrompt());

            String answer = geminiService.generateContent(request.getApiKey(), finalPrompt.toString());
            return ApiResult.success(answer);
            
        } catch (IllegalArgumentException e) {
            return ApiResult.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResult.error(500, e.getMessage());
        }
    }
}
