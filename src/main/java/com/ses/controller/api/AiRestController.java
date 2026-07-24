package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.config.AiConfig;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.ai.AiTextService;
import com.ses.service.security.DataScopeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import com.ses.common.util.PriceFormatter;
import org.springframework.web.bind.annotation.*;

/**
 * AI対話系APIコントローラー。
 * エンジニア・案件コンテキストを付加してAIと対話するエンドポイント。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiRestController {

    private final AiTextService aiTextService;
    private final EngineerService engineerService;
    private final ProjectService projectService;
    private final DataScopeService dataScopeService;
    private final AiConfig aiConfig;

    @Data
    public static class AiChatRequest {
        private String prompt;
        private Long engineerId;
        private Long projectId;
    }

    /**
     * AIとの対話エンドポイント。
     * エンジニアID・案件IDが指定された場合はコンテキストを付加する。
     */
    @PostMapping("/chat")
    public ApiResult<String> chat(@RequestBody AiChatRequest request) {
        if (!aiConfig.isEnabled()) {
            return ApiResult.error(400, "AI機能は現在無効化されています。");
        }
        try {
            StringBuilder finalPrompt = new StringBuilder();

            if (request.getEngineerId() != null) {
                dataScopeService.assertAllowedEngineer(request.getEngineerId());
                Engineer eng = engineerService.getById(request.getEngineerId());
                if (eng != null) {
                    finalPrompt.append("【要員情報】\n")
                               .append("- 氏名(ｲﾆｼｬﾙ): ").append(eng.getInitialName() != null ? eng.getInitialName() : eng.getFullName()).append("\n")
                               .append("- 経験年数: ").append(eng.getExperienceYears()).append("年\n")
                               .append("- 希望単価: ").append(PriceFormatter.format(eng.getExpectedUnitPrice())).append("\n")
                               .append("- スキル概要: ").append(eng.getResumeSummary() != null ? eng.getResumeSummary() : "なし").append("\n\n");
                }
            }

            if (request.getProjectId() != null) {
                dataScopeService.assertAllowedProject(request.getProjectId());
                Project proj = projectService.getById(request.getProjectId());
                if (proj != null) {
                    finalPrompt.append("【案件情報】\n")
                               .append("- 案件名: ").append(proj.getProjectName()).append("\n")
                               .append("- 単価幅: ").append(PriceFormatter.format(proj.getUnitPriceMin())).append("〜").append(PriceFormatter.format(proj.getUnitPriceMax())).append("\n")
                               .append("- リモート: ").append(proj.getRemoteType()).append("\n")
                               .append("- 案件詳細: ").append(proj.getDescription() != null ? proj.getDescription() : "なし").append("\n\n");
                }
            }

            finalPrompt.append("【ユーザーからの指示】\n").append(request.getPrompt());

            String answer = aiTextService.generate(finalPrompt.toString());
            return ApiResult.success(answer);

        } catch (com.ses.common.exception.BusinessException e) {
            return ApiResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return ApiResult.error(500, "AI呼び出し中にエラーが発生しました。");
        }
    }
}
