package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.config.AiConfig;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.ai.AiAllowlistFields;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
import com.ses.service.security.DataScopeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI対話系API。送信fieldはG10 allowlistのみ。ユーザー文は untrusted。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiRestController {

    private final AiExecutionGateway aiExecutionGateway;
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

    @PostMapping("/chat")
    public ApiResult<String> chat(@RequestBody AiChatRequest request) {
        if (!aiConfig.isEnabled()) {
            return ApiResult.error(400, "AI機能は現在無効化されています。");
        }
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            if (request.getEngineerId() != null) {
                dataScopeService.assertAllowedEngineer(request.getEngineerId());
                Engineer eng = engineerService.getById(request.getEngineerId());
                fields.putAll(AiAllowlistFields.engineer(eng, null));
            }
            if (request.getProjectId() != null) {
                dataScopeService.assertAllowedProject(request.getProjectId());
                Project proj = projectService.getById(request.getProjectId());
                fields.putAll(AiAllowlistFields.project(proj));
            }
            AiGatewayResult result = aiExecutionGateway.execute(AiGatewayRequest.builder()
                    .useCase(AiGatewayRequest.USE_CHAT)
                    .trustedInstruction("SES営業アシスタントとして、ALLOWLIST_CONTEXT のみを根拠に簡潔に答えてください。HTMLは出力しないでください。")
                    .allowlistedFields(fields)
                    .untrustedSourceText(request.getPrompt())
                    .persistRun(true)
                    .requireJson(false)
                    .build());
            return ApiResult.success(result.getText());
        } catch (com.ses.common.exception.BusinessException e) {
            return ApiResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return ApiResult.error(500, "AI呼び出し中にエラーが発生しました。");
        }
    }
}
