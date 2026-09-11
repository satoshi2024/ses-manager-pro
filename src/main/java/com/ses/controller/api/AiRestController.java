package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.ai.AiAllowlistFields;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
import com.ses.service.security.DataScopeService;
import com.ses.service.ai.LegacyAiEndpointBoundary;
import com.ses.service.ai.copilot.CopilotExecutionContext;
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
    private final LegacyAiEndpointBoundary endpointBoundary;

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
        // match/proposal-draftと同じ統一gate。BusinessExceptionはGlobalExceptionHandlerへ渡しHTTP 503等にする。
        CopilotExecutionContext context;
        if (request.getEngineerId() != null || request.getProjectId() != null) {
            context = endpointBoundary.createContext();
        } else {
            endpointBoundary.assertEndpointAllowed();
            context = null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (request.getEngineerId() != null) {
            Engineer eng = endpointBoundary.assertEngineer(request.getEngineerId(), context);
            fields.putAll(AiAllowlistFields.engineer(eng, null));
        }
        if (request.getProjectId() != null) {
            Project proj = endpointBoundary.assertProject(request.getProjectId(), context);
            fields.putAll(AiAllowlistFields.project(proj));
        }
        AiGatewayResult result = aiExecutionGateway.execute(AiGatewayRequest.legacyChat(
                        request.getEngineerId(), request.getProjectId(), context)
                .trustedInstruction("SES営業アシスタントとして、ALLOWLIST_CONTEXT のみを根拠に簡潔に答えてください。HTMLは出力しないでください。")
                .allowlistedFields(fields)
                .untrustedSourceText(request.getPrompt())
                .persistRun(true)
                .requireJson(false)
                .build());
        return ApiResult.success(result.getText());
    }
}
