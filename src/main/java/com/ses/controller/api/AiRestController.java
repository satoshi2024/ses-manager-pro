package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.config.AiConfig;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.GeminiService;
import com.ses.service.security.DataScopeService;
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
    private final DataScopeService dataScopeService;
    private final AiConfig aiConfig;

    @Data
    public static class AiChatRequest {
        private String apiKey;
        private String prompt;
        private Long engineerId;
        private Long projectId;
    }

    @PostMapping("/chat")
    public ApiResult<String> chat(@RequestBody AiChatRequest request) {
        if (!aiConfig.isEnabled()) {
            return ApiResult.error(400, "AI機能は現在無効化されています。");
        }
        try {
            StringBuilder finalPrompt = new StringBuilder();

            // コンテキストの追加
            if (request.getEngineerId() != null) {
                dataScopeService.assertAllowedEngineer(request.getEngineerId());
                Engineer eng = engineerService.getById(request.getEngineerId());
                if (eng != null) {
                    finalPrompt.append("【要員情報】\n")
                               .append("- 氏名(ｲﾆｼｬﾙ): ").append(eng.getInitialName() != null ? eng.getInitialName() : eng.getFullName()).append("\n")
                               .append("- 経験年数: ").append(eng.getExperienceYears()).append("年\n")
                               .append("- 希望単価: ").append(eng.getExpectedUnitPrice() != null ? eng.getExpectedUnitPrice() + "円" : "未設定").append("\n")
                               .append("- スキル概要: ").append(eng.getResumeSummary() != null ? eng.getResumeSummary() : "なし").append("\n\n");
                }
            }

            if (request.getProjectId() != null) {
                dataScopeService.assertAllowedProject(request.getProjectId());
                Project proj = projectService.getById(request.getProjectId());
                if (proj != null) {
                    finalPrompt.append("【案件情報】\n")
                               .append("- 案件名: ").append(proj.getProjectName()).append("\n")
                               .append("- 単価幅: ").append(proj.getUnitPriceMin() != null ? proj.getUnitPriceMin() + "円" : "未設定").append("〜").append(proj.getUnitPriceMax() != null ? proj.getUnitPriceMax() + "円" : "未設定").append("\n")
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

