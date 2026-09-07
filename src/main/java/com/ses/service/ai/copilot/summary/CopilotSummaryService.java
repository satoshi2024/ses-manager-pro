package com.ses.service.ai.copilot.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.AiArtifactVersion;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
import com.ses.service.ai.AiPiiMasker;
import com.ses.service.ai.copilot.result.MetricValue;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    public static final String TASK_MARKER = "[TASK:MANAGEMENT_COPILOT]";
    public static final String OUTPUT_SCHEMA_VERSION = "nf08-summary-1";

    private final AiExecutionGateway aiExecutionGateway;
    private final AiArtifactVersionMapper artifactVersionMapper;
    private final ObjectMapper objectMapper;

    public SummaryResponse summarize(TypedResultEnvelope envelope, String traceId) {
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

        AiArtifactVersion artifact = artifactVersionMapper.selectOne(new LambdaQueryWrapper<AiArtifactVersion>()
                .eq(AiArtifactVersion::getUseCase, AiGatewayRequest.USE_COPILOT)
                .eq(AiArtifactVersion::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (artifact == null) {
            return SummaryResponse.unavailable("ARTIFACT_MISSING");
        }

        SummaryRequest request = new SummaryRequest(
                envelope.queryId(),
                envelope.catalogVersion(),
                artifact.getPromptVersion(),
                OUTPUT_SCHEMA_VERSION,
                allowedClaimKeys,
                Duration.ofSeconds(15),
                0);

        long started = System.nanoTime();
        try {
            AiGatewayResult gatewayResult = aiExecutionGateway.execute(buildGatewayRequest(request, traceId));
            SummaryResponse parsed = parseProviderJson(gatewayResult.getText(), allowedClaimKeys);
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return new SummaryResponse(
                    parsed.summaryText(),
                    parsed.claimKeys(),
                    SummaryResponse.STATUS_SUCCEEDED,
                    artifact.getModelName(),
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

    private AiGatewayRequest buildGatewayRequest(SummaryRequest request, String traceId) {
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
                .trustedInstruction("""
                        Return JSON only: {"summaryText":"...","claimKeys":["..."]}.
                        summaryText must not contain HTML, numbers, currency symbols, or recalculated metrics.
                        claimKeys must be chosen only from summary.claimKeys in ALLOWLIST_CONTEXT.
                        """)
                .allowlistedFields(masked)
                .untrustedSourceText(null)
                .persistRun(false)
                .requireJson(true)
                .build();
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
        if (code == 429) {
            return SummaryResponse.unavailable("PROVIDER_429");
        }
        if (code == 500 && "PROVIDER_INVALID_JSON".equals(ex.getMessage())) {
            return SummaryResponse.unavailable("PROVIDER_INVALID_JSON");
        }
        return SummaryResponse.unavailable(SummaryResponse.STATUS_UNAVAILABLE);
    }
}
