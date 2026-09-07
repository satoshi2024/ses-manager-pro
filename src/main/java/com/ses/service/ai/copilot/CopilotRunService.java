package com.ses.service.ai.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * management copilotのcatalog実行runを既存AI ledgerへredacted記録する（F1）。
 */
@Service
@RequiredArgsConstructor
public class CopilotRunService {

    private final AiArtifactVersionMapper versionMapper;
    private final AiRecommendationRunMapper runMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public CopilotRunRecord recordQueryRun(
            SemanticCatalogEntry entry,
            String parameterHash,
            String scopeHash,
            int metricCount) {
        return insertRun(entry, parameterHash, scopeHash, "QUERY_EXECUTED", metricCount);
    }

    @Transactional(rollbackFor = Exception.class)
    public CopilotRunRecord recordCatalogRun(SemanticCatalogEntry entry, String parameterHash, String scopeHash) {
        return insertRun(entry, parameterHash, scopeHash, "CATALOG_RESOLVED", 0);
    }

    private CopilotRunRecord insertRun(
            SemanticCatalogEntry entry,
            String parameterHash,
            String scopeHash,
            String runKind,
            int metricCount) {
        AiArtifactVersion active = versionMapper.selectOne(new LambdaQueryWrapper<AiArtifactVersion>()
                .eq(AiArtifactVersion::getUseCase, AiGatewayRequest.USE_COPILOT)
                .eq(AiArtifactVersion::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (active == null) {
            throw new IllegalStateException("MANAGEMENT_COPILOT artifact が未登録です");
        }

        String traceId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", runKind);
        envelope.put("queryId", entry.queryId());
        envelope.put("catalogVersion", entry.catalogVersion());
        envelope.put("resultSchemaVersion", entry.resultSchemaVersion());
        envelope.put("parameterHash", parameterHash);
        envelope.put("scopeHash", scopeHash);
        envelope.put("dataVersion", "1");
        envelope.put("metricCount", metricCount);
        envelope.put("provider", active.getProvider());
        envelope.put("modelVersion", active.getModelName());
        envelope.put("promptVersion", active.getPromptVersion());

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException("run metadataのシリアライズに失敗しました", ex);
        }

        AiRecommendationRun run = new AiRecommendationRun();
        run.setTraceId(traceId);
        run.setUseCase(AiGatewayRequest.USE_COPILOT);
        run.setArtifactVersionId(active.getId());
        run.setActorUserId(SecurityUtils.currentUserId());
        run.setInputHash(sha256(json));
        run.setRedactedSummaryJson(json);
        run.setLatencyMs(0);
        run.setCostJpy(0);
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);

        return new CopilotRunRecord(run.getId(), traceId, entry.queryId(), entry.catalogVersion());
    }

    public record CopilotRunRecord(Long runId, String traceId, String queryId, String catalogVersion) {
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return "0".repeat(64);
        }
    }
}
