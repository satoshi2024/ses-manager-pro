package com.ses.service.ai;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;

@Data
@Builder
public class AiGatewayRequest {

    public static final String USE_MATCHING = "MATCHING";
    public static final String USE_PROPOSAL_DRAFT = "PROPOSAL_DRAFT";
    public static final String USE_CHAT = "CHAT";
    public static final String USE_INGEST_RESUME = "INGEST_RESUME";
    public static final String USE_INGEST_PROJECT = "INGEST_PROJECT";
    public static final String USE_INGEST_BP = "INGEST_BP_AVAILABILITY";
    public static final String USE_LEARNING_CANDIDATE = "LEARNING_CANDIDATE";
    public static final String USE_COPILOT = "MANAGEMENT_COPILOT";

    public static final String CANARY = "SES-PII-CANARY-T109-7f2e9c1a";

    private String useCase;
    private String traceId;
    private String trustedInstruction;
    private String taskMarker;
    @Builder.Default
    private Map<String, Object> allowlistedFields = new LinkedHashMap<>();
    private String untrustedSourceText;
    private boolean persistRun;
    private Long actorUserId;
    private boolean requireJson;
    /** resourceを含むlegacy requestはExecutionContext/scope snapshotを必須にする。 */
    @Builder.Default
    private boolean resourceBearing = false;
    /** builderのbooleanだけではresource有無を表せないよう、typed IDも保持する。 */
    private Long engineerId;
    private Long projectId;

    /** MANAGEMENT_COPILOTでは必須。gatewayでidentityを再検証するための非永続コンテキスト。 */
    private CopilotExecutionContext executionContext;
    private CopilotScopeContext scopeContext;
    private String scopeHash;

    public static AiGatewayRequestBuilder legacyChat(Long engineerId, Long projectId,
                                                     CopilotExecutionContext context) {
        return builder().useCase(USE_CHAT).engineerId(engineerId).projectId(projectId)
                .executionContext(context)
                .scopeContext(context == null ? null : context.scope())
                .scopeHash(context == null ? null : context.scopeHash())
                .resourceBearing(engineerId != null || projectId != null);
    }

    public static AiGatewayRequestBuilder legacyMatching(CopilotExecutionContext context) {
        return builder().useCase(USE_MATCHING).executionContext(context)
                .scopeContext(context == null ? null : context.scope())
                .scopeHash(context == null ? null : context.scopeHash())
                .resourceBearing(true);
    }

    public static AiGatewayRequestBuilder legacyProposalDraft(CopilotExecutionContext context) {
        return builder().useCase(USE_PROPOSAL_DRAFT).executionContext(context)
                .scopeContext(context == null ? null : context.scope())
                .scopeHash(context == null ? null : context.scopeHash())
                .resourceBearing(true);
    }

    public boolean hasTypedResourceFields() {
        return engineerId != null || projectId != null;
    }

    /**
     * resourceBearingのbooleanを呼び出し側がfalseへ戻しても、allow-listへ資源項目を
     * 入れたrequestはresource-bearingとして扱う。resource判定をmutable flagだけに
     * 任せると、context必須境界をbuilderから迂回できるためである。
     */
    public boolean isResourceBearing() {
        if (resourceBearing || hasTypedResourceFields() || allowlistedFields == null) {
            return resourceBearing || hasTypedResourceFields();
        }
        return allowlistedFields.keySet().stream().anyMatch(AiGatewayRequest::isResourceField);
    }

    private static boolean isResourceField(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return Set.of("engineer.", "project.", "contract.", "customer.",
                "invoice.", "bp.").stream().anyMatch(key::startsWith);
    }
}
