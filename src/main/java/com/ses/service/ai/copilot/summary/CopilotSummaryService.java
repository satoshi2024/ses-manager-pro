package com.ses.service.ai.copilot.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
import com.ses.service.ai.AiPiiMasker;
import com.ses.service.ai.copilot.CopilotRunService;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mock/rule summary provider（B1）。唯一の AiExecutionGateway 呼び出し点。
 * F2 pipeline（catalog/scope/service）は変更しない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotSummaryService {

    public static final String TASK_MARKER = CopilotSummaryPromptTemplate.TASK_MARKER;
    public static final String OUTPUT_SCHEMA_VERSION = CopilotSummaryPromptTemplate.OUTPUT_SCHEMA_VERSION;

    private final AiExecutionGateway aiExecutionGateway;
    private final ObjectMapper objectMapper;
    private final CopilotFeatureGate featureGate;

    public SummaryResponse summarize(TypedResultEnvelope envelope, CopilotExecutionContext context,
                                     CopilotScopeContext scope, String scopeHash,
                                     CopilotRunService.CopilotArtifactSnapshot artifact, String traceId) {
        long started = System.nanoTime();
        long deadlineNanos = started + Duration.ofSeconds(15).toNanos();
        if (!isBound(envelope, context, scope, scopeHash)) {
            return SummaryResponse.unavailable(SummaryResponse.STATUS_UNAVAILABLE);
        }
        try {
            featureGate.assertQueryAllowed();
        } catch (BusinessException ex) {
            return SummaryResponse.unavailable(SummaryResponse.STATUS_UNAVAILABLE);
        }
        if (envelope == null || envelope.values() == null || envelope.values().isEmpty()) {
            return SummaryResponse.unavailable(SummaryResponse.STATUS_UNAVAILABLE);
        }
        List<String> allowedClaimKeys = envelope.values().stream()
                .map(MetricValue::key)
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (allowedClaimKeys.isEmpty()) {
            return SummaryResponse.unavailable(SummaryResponse.STATUS_UNAVAILABLE);
        }

        if (artifact == null) {
            return SummaryResponse.unavailable("ARTIFACT_MISSING");
        }

        SummaryRequest request = new SummaryRequest(
                envelope.queryId(),
                envelope.catalogVersion(),
                artifact.promptVersion(),
                OUTPUT_SCHEMA_VERSION,
                allowedClaimKeys,
                Duration.ofSeconds(15),
                0);

        try {
            AiGatewayResult gatewayResult = aiExecutionGateway.execute(
                    buildGatewayRequest(request, context, scope, scopeHash, traceId, artifact, deadlineNanos));
            SummaryResponse parsed = parseProviderJson(gatewayResult.getText(), allowedClaimKeys);
            if (System.nanoTime() >= deadlineNanos) {
                return SummaryResponse.unavailable("COPILOT_DEADLINE_EXCEEDED");
            }
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return new SummaryResponse(
                    parsed.summaryText(),
                    parsed.claimKeys(),
                    SummaryResponse.STATUS_SUCCEEDED,
                    artifact.modelName(),
                    latencyMs,
                    null,
                    0);
        } catch (CopilotSummaryValidationException ex) {
            log.debug("copilot summary rejected: {}", ex.reasonCode());
            return SummaryResponse.unavailable(SummaryResponse.STATUS_REJECTED);
        } catch (BusinessException ex) {
            log.debug("copilot summary provider error: code={}", ex.getCode());
            return mapProviderError(ex);
        } catch (RuntimeException ex) {
            log.debug("copilot summary provider failure", ex);
            return SummaryResponse.unavailable(SummaryResponse.STATUS_UNAVAILABLE);
        }
    }

    private AiGatewayRequest buildGatewayRequest(SummaryRequest request, CopilotExecutionContext context,
                                                  CopilotScopeContext scope, String scopeHash, String traceId,
                                                  CopilotRunService.CopilotArtifactSnapshot artifact,
                                                  long deadlineNanos) {
        Map<String, Object> allowlisted = new LinkedHashMap<>();
        allowlisted.put("catalog.queryId", request.queryId());
        allowlisted.put("catalog.catalogVersion", request.catalogVersion());
        allowlisted.put("summary.claimKeys", String.join(",", request.allowedClaimKeys()));
        Map<String, Object> masked = AiPiiMasker.maskCopilot(allowlisted);
        if (AiPiiMasker.containsCanary(masked)) {
            throw new CopilotSummaryValidationException("PII_CANARY");
        }

        return AiGatewayRequest.builder()
                .useCase(AiGatewayRequest.USE_COPILOT)
                .traceId(traceId)
                .taskMarker(TASK_MARKER)
                .trustedInstruction(CopilotSummaryPromptTemplate.TRUSTED_INSTRUCTION)
                .allowlistedFields(masked)
                .untrustedSourceText(null)
                .persistRun(false)
                .requireJson(true)
                .executionContext(context)
                .scopeContext(scope)
                .scopeHash(scopeHash)
                .deadlineNanos(deadlineNanos)
                .costBudgetYen(request.costBudgetYen())
                .expectedArtifactVersionId(artifact.artifactVersionId())
                .expectedProvider(artifact.provider())
                .expectedModel(artifact.modelName())
                .expectedPromptVersion(artifact.promptVersion())
                .expectedConfigHash(artifact.configHash())
                .build();
    }

    private boolean isBound(TypedResultEnvelope envelope, CopilotExecutionContext context,
                            CopilotScopeContext scope, String scopeHash) {
        return envelope != null && context != null && scope != null && scopeHash != null
                && context.effectiveScopeSnapshot() != null
                && context.scope() == context.effectiveScopeSnapshot().scope()
                && context.scope() == scope && context.scopeHash() != null
                && context.scopeHash().equals(scopeHash)
                && context.queryId() != null && context.parameters() != null
                && context.queryId().equals(context.parameters().queryId())
                && context.queryId().equals(envelope.queryId())
                && context.asOf().equals(envelope.asOf())
                && context.zoneId().getId().equals(envelope.tenantTimezone())
                && java.util.Objects.equals(scope.tenantId(), context.tenantId())
                && java.util.Objects.equals(scope.legalEntityId(), context.legalEntityId())
                && envelope.scope() != null
                && scope.scopeHash().equals(envelope.scope().hash());
    }

    private SummaryResponse parseProviderJson(String text, List<String> allowedClaimKeys) {
        try {
            String json = text == null ? "" : text.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-zA-Z]*\\s*", "").replace("```", "").trim();
            }
            JsonNode node = objectMapper.readTree(json);
            JsonNode summaryNode = node.path("summaryText");
            String summaryText = summaryNode.isMissingNode() || summaryNode.isNull() ? null : summaryNode.asText();
            List<String> claimKeys = parseClaimKeys(node.path("claimKeys"));
            CopilotSummaryValidator.validateSummaryText(summaryText);
            CopilotSummaryValidator.validateClaimKeys(claimKeys, allowedClaimKeys);
            return new SummaryResponse(
                    summaryText,
                    claimKeys,
                    SummaryResponse.STATUS_SUCCEEDED,
                    null,
                    0L,
                    null,
                    null);
        } catch (CopilotSummaryValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "PROVIDER_INVALID_JSON");
        }
    }

    private List<String> parseClaimKeys(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new BusinessException(500, "PROVIDER_INVALID_JSON");
        }
        return objectMapper.convertValue(node,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private SummaryResponse mapProviderError(BusinessException ex) {
        int code = ex.getCode();
        if ("COPILOT_COST_UNKNOWN".equals(ex.getMessage())) {
            return SummaryResponse.unavailable("COST_UNKNOWN");
        }
        if ("COPILOT_DEADLINE_EXCEEDED".equals(ex.getMessage())) {
            return SummaryResponse.unavailable("DEADLINE_EXCEEDED");
        }
        if ("COPILOT_PROMPT_VERSION_MISMATCH".equals(ex.getMessage())) {
            return SummaryResponse.unavailable("PROMPT_VERSION_MISMATCH");
        }
        if ("COPILOT_CONFIG_HASH_MISMATCH".equals(ex.getMessage())) {
            return SummaryResponse.unavailable("CONFIG_HASH_MISMATCH");
        }
        if ("COPILOT_ARTIFACT_PROVIDER_MISMATCH".equals(ex.getMessage())) {
            return SummaryResponse.unavailable("ARTIFACT_PROVIDER_MISMATCH");
        }
        if ("COPILOT_BOUNDED_PROVIDER_MISMATCH".equals(ex.getMessage())) {
            return SummaryResponse.unavailable("BOUNDED_PROVIDER_MISMATCH");
        }
        if ("COPILOT_EXECUTION_SNAPSHOT_INVALID".equals(ex.getMessage())) {
            return SummaryResponse.unavailable("ARTIFACT_SNAPSHOT_INVALID");
        }
        if (code == 429) {
            return SummaryResponse.unavailable("PROVIDER_429");
        }
        if (code == 500 && "PROVIDER_INVALID_JSON".equals(ex.getMessage())) {
            return SummaryResponse.unavailable("PROVIDER_INVALID_JSON");
        }
        return SummaryResponse.unavailable(SummaryResponse.STATUS_UNAVAILABLE);
    }
}
