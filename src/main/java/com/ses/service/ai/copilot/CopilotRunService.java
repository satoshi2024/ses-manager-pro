package com.ses.service.ai.copilot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.summary.SummaryResponse;
import com.ses.service.ai.copilot.summary.SummaryCostEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** MANAGEMENT_COPILOTのartifact snapshotとredacted attemptを記録する。 */
@Service
@RequiredArgsConstructor
public class CopilotRunService {

    private final AiArtifactVersionMapper versionMapper;
    private final AiRecommendationRunMapper runMapper;
    private final ObjectMapper objectMapper;

    /** summaryより前に1回だけACTIVE artifactを読み、後段へ不変値を渡す。 */
    @Transactional(readOnly = true)
    public CopilotArtifactSnapshot pinActiveArtifact() {
        AiArtifactVersion active = versionMapper.selectOne(new LambdaQueryWrapper<AiArtifactVersion>()
                .eq(AiArtifactVersion::getUseCase, AiGatewayRequest.USE_COPILOT)
                .eq(AiArtifactVersion::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (active == null || active.getId() == null
                || active.getProvider() == null || active.getProvider().isBlank()
                || active.getModelName() == null || active.getModelName().isBlank()
                || active.getPromptVersion() == null || active.getPromptVersion().isBlank()
                || active.getConfigHash() == null || active.getConfigHash().isBlank()) {
            throw BusinessException.of(503, "COPILOT_ARTIFACT_UNAVAILABLE");
        }
        return new CopilotArtifactSnapshot(
                active.getId(), active.getProvider(), active.getModelName(), active.getPromptVersion(),
                active.getConfigHash(), active.getRuleVersion(), active.getStatusVersion());
    }

    @Transactional(rollbackFor = Exception.class)
    public CopilotAttemptRecord beginQueryAttempt(
            SemanticCatalogEntry entry,
            String parameterHash,
            CopilotExecutionContext context,
            CopilotArtifactSnapshot artifact) {
        assertBoundContext(context, entry, artifact);
        Map<String, Object> metadata = baseMetadata("QUERY", entry, parameterHash, context, artifact, null);
        metadata.put("outcome", "PENDING");
        metadata.put("costState", "NOT_APPLICABLE");
        return insertAttempt(metadata, entry, parameterHash, context, artifact, null, "QUERY");
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeQueryAttempt(CopilotAttemptRecord attempt, long latencyMs,
                                     boolean succeeded, int metricCount) {
        if (attempt == null || !"QUERY".equals(attempt.attemptKind())) {
            throw new IllegalArgumentException("query attempt is required");
        }
        completeAttempt(attempt, latencyMs, succeeded ? "SUCCEEDED" : "FAILED",
                succeeded ? null : "QUERY_EXECUTION_FAILED", null, "NOT_APPLICABLE",
                succeeded ? "SUCCEEDED" : "FAILED", metricCount, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public CopilotAttemptRecord beginSummaryAttempt(
            SemanticCatalogEntry entry,
            String parameterHash,
            CopilotExecutionContext context,
            CopilotArtifactSnapshot artifact,
            CopilotAttemptRecord queryAttempt) {
        assertBoundContext(context, entry, artifact);
        if (queryAttempt == null || !"QUERY".equals(queryAttempt.attemptKind())
                || !artifact.equals(queryAttempt.artifact())) {
            throw new IllegalArgumentException("summary must use the query artifact snapshot");
        }
        Map<String, Object> metadata = baseMetadata("SUMMARY", entry, parameterHash, context,
                artifact, queryAttempt.traceId());
        metadata.put("outcome", "PENDING");
        metadata.put("costState", "UNKNOWN");
        return insertAttempt(metadata, entry, parameterHash, context, artifact,
                queryAttempt.traceId(), "SUMMARY");
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeSummaryAttempt(CopilotAttemptRecord attempt, long latencyMs,
                                       SummaryResponse response) {
        if (attempt == null || !"SUMMARY".equals(attempt.attemptKind()) || response == null) {
            throw new IllegalArgumentException("summary attempt and outcome are required");
        }
        boolean succeeded = response.isAvailable();
        SummaryCostEvidence evidence = response.costEvidence() == null
                ? SummaryCostEvidence.unknown("COST_EVIDENCE_MISSING") : response.costEvidence();
        Integer cost = evidence.costState() == com.ses.service.ai.copilot.summary.CopilotCostState.NOT_SENT
                ? 0 : evidence.costState() == com.ses.service.ai.copilot.summary.CopilotCostState.ACTUAL
                ? evidence.actualCostYen() : null;
        String costState = evidence.costState().name();
        String providerStatus = safeOutcome(response.providerStatus());
        completeAttempt(attempt, latencyMs, succeeded ? "SUCCEEDED" : "FAILED",
                succeeded ? null : providerStatus, cost, costState, providerStatus, null, evidence);
    }

    /** 旧呼出し経路は認可済みcontextを渡せないため拒否する。 */
    @Transactional(rollbackFor = Exception.class)
    public CopilotRunRecord recordQueryRun(
            SemanticCatalogEntry entry, String parameterHash, String scopeHash, int metricCount) {
        throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    @Transactional(rollbackFor = Exception.class)
    public CopilotRunRecord recordCatalogRun(SemanticCatalogEntry entry, String parameterHash, String scopeHash) {
        throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    private CopilotAttemptRecord insertAttempt(
            Map<String, Object> metadata,
            SemanticCatalogEntry entry,
            String parameterHash,
            CopilotExecutionContext context,
            CopilotArtifactSnapshot artifact,
            String parentTraceId,
            String attemptKind) {
        String traceId = UUID.randomUUID().toString();
        metadata.put("traceId", traceId);
        String json = toJson(metadata);

        AiRecommendationRun run = new AiRecommendationRun();
        run.setTenantId(context.tenantId());
        run.setTraceId(traceId);
        run.setUseCase(AiGatewayRequest.USE_COPILOT);
        run.setArtifactVersionId(artifact.artifactVersionId());
        run.setActorUserId(SecurityUtils.currentUserId());
        run.setInputHash(sha256(json));
        run.setRedactedSummaryJson(json);
        run.setLatencyMs(null);
        run.setCostJpy(null);
        run.setStatus("PENDING");
        run.setStatusVersion(0);
        run.setScopeHash(context.scopeHash());
        run.setParameterHash(parameterHash);
        run.setLegalEntityId(context.legalEntityId());
        run.setAsOfAt(LocalDateTime.ofInstant(context.asOf(), ZoneOffset.UTC));
        run.setTimezoneId(context.zoneId().getId());
        run.setCatalogVersion(entry.catalogVersion());
        run.setDataVersion("1");
        run.setCreatedAt(LocalDateTime.ofInstant(context.asOf(), ZoneOffset.UTC));
        runMapper.insert(run);

        return new CopilotAttemptRecord(run.getId(), traceId, parentTraceId, attemptKind,
                context.tenantId(), artifact, Map.copyOf(metadata));
    }

    private Map<String, Object> baseMetadata(
            String attemptKind, SemanticCatalogEntry entry, String parameterHash,
            CopilotExecutionContext context, CopilotArtifactSnapshot artifact, String parentTraceId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attemptKind", attemptKind);
        if (parentTraceId != null) {
            metadata.put("parentTraceId", parentTraceId);
        }
        metadata.put("queryId", entry.queryId());
        metadata.put("catalogVersion", entry.catalogVersion());
        metadata.put("resultSchemaVersion", entry.resultSchemaVersion());
        metadata.put("dataVersion", "1");
        metadata.put("parameterHash", parameterHash);
        metadata.put("scopeHash", context.scopeHash());
        metadata.put("artifactVersionId", artifact.artifactVersionId());
        metadata.put("provider", artifact.provider());
        metadata.put("modelName", artifact.modelName());
        metadata.put("promptVersion", artifact.promptVersion());
        metadata.put("configHash", artifact.configHash());
        if (artifact.ruleVersion() != null) {
            metadata.put("ruleVersion", artifact.ruleVersion());
        }
        if (artifact.statusVersion() != null) {
            metadata.put("artifactStatusVersion", artifact.statusVersion());
        }
        return metadata;
    }

    private void completeAttempt(CopilotAttemptRecord attempt, long latencyMs, String runStatus,
                                 String errorCode, Integer costJpy, String costState,
                                 String outcome, Integer metricCount, SummaryCostEvidence costEvidence) {
        Map<String, Object> metadata = new LinkedHashMap<>(attempt.metadata());
        metadata.put("outcome", outcome);
        metadata.put("latencyMs", boundedLatency(latencyMs));
        metadata.put("costState", costState);
        if (costEvidence != null) {
            metadata.put("dispatchOutcome", costEvidence.dispatchOutcome().state().name());
            putSafeCode(metadata, "dispatchReasonCode", costEvidence.dispatchOutcome().reasonCode());
            putSafeCode(metadata, "costReasonCode", costEvidence.reasonCode());
            if (costEvidence.upperBoundYen() != null) {
                metadata.put("costUpperBoundYen", costEvidence.upperBoundYen());
            }
            if (costEvidence.preflight() != null) {
                putSafeIdentity(metadata, "costPolicyId", costEvidence.preflight().policyId());
                putSafeIdentity(metadata, "costConfigIdentity", costEvidence.preflight().configIdentity());
                putSafeIdentity(metadata, "costProofIdentity", costEvidence.preflight().proofIdentity());
            }
        }
        if (costJpy != null) {
            metadata.put("costJpy", costJpy);
        }
        if (metricCount != null) {
            metadata.put("metricCount", metricCount);
        }
        String json = toJson(metadata);

        LambdaUpdateWrapper<AiRecommendationRun> update = new LambdaUpdateWrapper<AiRecommendationRun>()
                .eq(AiRecommendationRun::getId, attempt.runId())
                .eq(AiRecommendationRun::getTenantId, attempt.tenantId())
                .eq(AiRecommendationRun::getTraceId, attempt.traceId())
                .eq(AiRecommendationRun::getUseCase, AiGatewayRequest.USE_COPILOT)
                .eq(AiRecommendationRun::getStatus, "PENDING")
                .eq(AiRecommendationRun::getStatusVersion, 0)
                .set(AiRecommendationRun::getStatus, runStatus)
                .set(AiRecommendationRun::getStatusVersion, 1)
                .set(AiRecommendationRun::getLatencyMs, boundedLatency(latencyMs))
                .set(AiRecommendationRun::getCostJpy, costJpy)
                .set(AiRecommendationRun::getErrorCode, errorCode)
                .set(AiRecommendationRun::getRedactedSummaryJson, json)
                .set(AiRecommendationRun::getInputHash, sha256(json));
        if (runMapper.update(null, update) != 1) {
            throw new IllegalStateException("Copilot attempt outcome was not committed");
        }
    }

    private void putSafeCode(Map<String, Object> metadata, String key, String value) {
        if (value != null && value.matches("[A-Z0-9_]{1,64}")) metadata.put(key, value);
    }

    private void putSafeIdentity(Map<String, Object> metadata, String key, String value) {
        if (value != null && value.matches("[A-Za-z0-9._:-]{1,128}")) metadata.put(key, value);
    }

    private void assertBoundContext(CopilotExecutionContext context, SemanticCatalogEntry entry,
                                    CopilotArtifactSnapshot artifact) {
        if (context == null || entry == null || artifact == null
                || context.queryId() == null || context.parameters() == null || context.scope() == null
                || context.scopeHash() == null || !context.scopeHash().equals(context.scope().scopeHash())
                || !entry.queryId().equals(context.queryId())
                || !context.queryId().equals(context.parameters().queryId())
                || !context.tenantId().equals(AccountingTenantContextHolder.requireTenantContext())) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("redacted Copilot metadata serialization failed", ex);
        }
    }

    private String safeOutcome(String value) {
        if (value == null || value.isBlank() || value.length() > 64
                || !value.matches("[A-Z0-9_]+")) {
            return "SUMMARY_UNAVAILABLE";
        }
        return value;
    }

    private int boundedLatency(long latencyMs) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, latencyMs));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("run metadata hash failed", ex);
        }
    }

    public record CopilotArtifactSnapshot(Long artifactVersionId, String provider, String modelName,
                                          String promptVersion, String configHash, String ruleVersion,
                                          Integer statusVersion) {
    }

    public record CopilotAttemptRecord(Long runId, String traceId, String parentTraceId,
                                       String attemptKind, String tenantId,
                                       CopilotArtifactSnapshot artifact, Map<String, Object> metadata) {
    }

    /** Query API互換用のresult view。summary attemptは別traceで永続化する。 */
    public record CopilotRunRecord(Long runId, String traceId, String queryId, String catalogVersion) {
    }
}
