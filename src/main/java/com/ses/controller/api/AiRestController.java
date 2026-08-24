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

    /**
     * AI対話リクエスト。APIキーはサーバー側設定(ai.api-key)のみを使用するため、
     * クライアントからのAPIキー等の未知フィールドは受け付けない
     * （ACC-SEC-P1-004: 旧 apiKey フィールドをサイレントに無視・使用しない）。
     *
     * <p>未知フィールドは {@link com.fasterxml.jackson.annotation.JsonAnySetter} で「フィールド名のみ」を
     * 捕捉し（値は保持・エコーしない）、コントローラー側で 400 として拒否する。
     * Spring の既定 ObjectMapper は FAIL_ON_UNKNOWN_PROPERTIES=false のため、
     * DTO 側で明示的に検出する必要がある。
     */
    public static class AiChatRequest {
        private String prompt;
        private Long engineerId;
        private Long projectId;
        private final java.util.Set<String> unknownFields = new java.util.LinkedHashSet<>();

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public Long getEngineerId() { return engineerId; }
        public void setEngineerId(Long engineerId) { this.engineerId = engineerId; }
        public Long getProjectId() { return projectId; }
        public void setProjectId(Long projectId) { this.projectId = projectId; }

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void putUnknown(String name, Object value) {
            // 値(APIキー等)は保持しない。名前のみ記録して拒否判定に用いる。
            unknownFields.add(name);
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public java.util.Set<String> getUnknownFields() {
            return unknownFields;
        }
    }

    @PostMapping("/chat")
    public ApiResult<String> chat(@RequestBody AiChatRequest request) {
        if (!request.getUnknownFields().isEmpty()) {
            // 旧 apiKey を含む未知フィールドはサイレントに無視せず拒否する（値はエコーしない）。
            return ApiResult.error(400, "許可されていないフィールドが含まれています。APIキーはサーバー側で管理されます。");
        }
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
