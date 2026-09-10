package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.ai.MatchResultDto;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationItem;
import com.ses.entity.AiRecommendationRun;
import com.ses.entity.BpAvailability;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationItemMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.ai.AiAllowlistFields;
import com.ses.service.ai.AiPiiMasker;
import com.ses.service.ai.AiRecommendationRecorder;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.TenantOwnershipResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiRecommendationRecorderImpl implements AiRecommendationRecorder {

    private static final String LEGACY_QUERY_ID = "legacy.ai";
    private static final String LEGACY_CATALOG_VERSION = "legacy-matching-v1";
    private static final String LEGACY_DATA_VERSION = "resource-scope-v1";

    private final AiArtifactVersionMapper versionMapper;
    private final AiRecommendationRunMapper runMapper;
    private final AiRecommendationItemMapper itemMapper;
    private final BpAvailabilityMapper bpAvailabilityMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final EngineerSkillMapper engineerSkillMapper;
    private final ObjectMapper objectMapper;
    private final TenantOwnershipResolver tenantOwnershipResolver;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String recordMatch(String useCase, Long actorUserId, List<MatchResultDto> results,
                              Long sourceEngineerId, Long sourceProjectId,
                              CopilotExecutionContext context) {
        EffectiveScopeSnapshot snapshot = requireSnapshot(context);
        Long currentActorId = SecurityUtils.currentUserId();
        String currentTenantId = SecurityUtils.currentTenantId();
        if (currentActorId == null || (actorUserId != null && !actorUserId.equals(currentActorId))) {
            throw denied("ACTOR_CONTEXT_REQUIRED");
        }
        if (currentTenantId == null || !currentTenantId.equals(context.tenantId())) {
            throw denied("TENANT_CONTEXT_REQUIRED");
        }
        if (useCase == null || useCase.isBlank()
                || (sourceEngineerId == null && sourceProjectId == null)) {
            throw denied("SCOPE_CONTEXT_REQUIRED");
        }
        if (results == null || results.isEmpty()) {
            return null;
        }
        validateSource(sourceEngineerId, sourceProjectId, snapshot);
        for (MatchResultDto result : results) {
            validateResult(result, snapshot);
        }
        String tenantId = context.tenantId();

        AiArtifactVersion active = versionMapper.selectOne(new LambdaQueryWrapper<AiArtifactVersion>()
                .eq(AiArtifactVersion::getUseCase, useCase)
                .eq(AiArtifactVersion::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (active == null) {
            return null;
        }
        MatchResultDto first = results.get(0);
        if (first == null) {
            throw com.ses.common.exception.BusinessException.of(400, "AI推薦候補が不正です");
        }
        Long engineerId = sourceEngineerId != null ? sourceEngineerId : first.getEngineerId();
        Long projectId = sourceProjectId != null ? sourceProjectId : first.getProjectId();
        validateTargetTenant(sourceEngineerId, sourceProjectId, tenantId);
        Map<String, Object> masked = AiPiiMasker.mask(AiAllowlistFields.merge(
                engineerFields(tenantId, engineerId),
                projectFields(tenantId, projectId)));
        String summaryJson = toJson(masked);
        String parameterHash = sha256(canonicalParameters(useCase, sourceEngineerId,
                sourceProjectId, results));

        String traceId = UUID.randomUUID().toString();
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTraceId(traceId);
        run.setTenantId(tenantId);
        run.setUseCase(useCase);
        run.setArtifactVersionId(active.getId());
        run.setActorUserId(currentActorId);
        run.setInputHash(sha256(summaryJson));
        run.setParameterHash(parameterHash);
        run.setScopeHash(snapshot.scopeHash());
        run.setTenantId(context.tenantId());
        run.setLegalEntityId(context.legalEntityId());
        run.setAsOfAt(LocalDateTime.ofInstant(context.asOf(), ZoneOffset.UTC));
        run.setTimezoneId(context.zoneId().getId());
        run.setCatalogVersion(LEGACY_CATALOG_VERSION);
        run.setDataVersion(LEGACY_DATA_VERSION);
        run.setRedactedSummaryJson(summaryJson);
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        run.setCostJpy(0);
        runMapper.insert(run);

        int rank = 1;
        for (MatchResultDto dto : results) {
            if (dto == null) {
                throw com.ses.common.exception.BusinessException.of(400, "AI推薦候補が不正です");
            }
            validateTargetTenant(dto, tenantId);
            AiRecommendationItem item = new AiRecommendationItem();
            item.setTenantId(tenantId);
            item.setRunId(run.getId());
            item.setRankNo(rank++);
            if (dto.getEngineerId() != null) {
                item.setTargetType("ENGINEER");
                item.setTargetId(dto.getEngineerId());
            } else if (dto.getProjectId() != null) {
                item.setTargetType("PROJECT");
                item.setTargetId(dto.getProjectId());
            } else if (dto.getBpAvailabilityId() != null) {
                item.setTargetType("BP");
                item.setTargetId(dto.getBpAvailabilityId());
            } else {
                item.setTargetType("UNKNOWN");
                item.setTargetId(0L);
            }
            if (dto.getScore() != null) {
                item.setScore(BigDecimal.valueOf(dto.getScore()));
            }
            item.setExplanationJson("{\"reason\":" + quote(dto.getReason()) + "}");
            item.setSelectedFlag(0);
            itemMapper.insert(item);
            dto.setRunId(run.getId());
            dto.setItemId(item.getId());
            dto.setTraceId(traceId);
        }
        return traceId;
    }

    private EffectiveScopeSnapshot requireSnapshot(CopilotExecutionContext context) {
        if (context == null || context.effectiveScopeSnapshot() == null
                || context.scope() == null || context.scope() != context.effectiveScopeSnapshot().scope()
                || context.tenantId() == null || context.tenantId().isBlank()
                || context.legalEntityId() == null || context.asOf() == null
                || context.scopeHash() == null
                || !context.tenantId().equals(context.effectiveScopeSnapshot().tenantId())
                || !context.legalEntityId().equals(context.effectiveScopeSnapshot().legalEntityId())
                || !context.tenantId().equals(context.scope().tenantId())
                || !context.legalEntityId().equals(context.scope().legalEntityId())
                || !context.asOfDate().equals(context.effectiveScopeSnapshot().asOf())
                || !context.scopeHash().equals(context.effectiveScopeSnapshot().scopeHash())
                || !context.scopeHash().equals(context.scope().scopeHash())
                || context.queryId() == null
                || context.parameters() == null
                || !LEGACY_QUERY_ID.equals(context.queryId())
                || !context.queryId().equals(context.parameters().queryId())) {
            throw denied("EXECUTION_CONTEXT_REQUIRED");
        }
        return context.effectiveScopeSnapshot();
    }

    private void validateSource(Long sourceEngineerId, Long sourceProjectId,
                                EffectiveScopeSnapshot snapshot) {
        if (sourceEngineerId != null) {
            Engineer source = engineerMapper.selectById(sourceEngineerId);
            if (source == null || source.getLegalEntityId() == null
                    || !snapshot.legalEntityId().equals(source.getLegalEntityId())
                    || !snapshot.allowsEngineer(sourceEngineerId)) {
                throw denied("SCOPE_CONTEXT_REQUIRED");
            }
        }
        if (sourceProjectId != null) {
            Project source = projectMapper.selectById(sourceProjectId);
            if (source == null || source.getLegalEntityId() == null
                    || !snapshot.legalEntityId().equals(source.getLegalEntityId())
                    || !snapshot.allowsProject(sourceProjectId)) {
                throw denied("SCOPE_CONTEXT_REQUIRED");
            }
        }
    }

    private void validateResult(MatchResultDto result, EffectiveScopeSnapshot snapshot) {
        if (result == null) {
            throw denied("SCOPE_CONTEXT_REQUIRED");
        }
        int targetKinds = (result.getEngineerId() == null ? 0 : 1)
                + (result.getProjectId() == null ? 0 : 1)
                + (result.getBpAvailabilityId() == null ? 0 : 1);
        if (targetKinds != 1) {
            throw denied("SCOPE_CONTEXT_REQUIRED");
        }
        if (result.getEngineerId() != null) {
            Engineer target = engineerMapper.selectById(result.getEngineerId());
            if (target == null || target.getLegalEntityId() == null
                    || !snapshot.legalEntityId().equals(target.getLegalEntityId())
                    || !snapshot.allowsEngineer(result.getEngineerId())) {
                throw denied("SCOPE_CONTEXT_REQUIRED");
            }
        }
        if (result.getProjectId() != null) {
            Project target = projectMapper.selectById(result.getProjectId());
            if (target == null || target.getLegalEntityId() == null
                    || !snapshot.legalEntityId().equals(target.getLegalEntityId())
                    || !snapshot.allowsProject(result.getProjectId())) {
                throw denied("SCOPE_CONTEXT_REQUIRED");
            }
        }
        if (result.getBpAvailabilityId() != null) {
            BpAvailability target = bpAvailabilityMapper.selectById(result.getBpAvailabilityId());
            // BPに組織/DataScopeのID集合がないため、全社・非DataScope時だけ保存する。
            if (target == null || target.getLegalEntityId() == null
                    || !snapshot.legalEntityId().equals(target.getLegalEntityId())
                    || !snapshot.organizationFullAccess() || snapshot.dataScoped()) {
                throw denied("SCOPE_CONTEXT_REQUIRED");
            }
        }
    }

    private static String canonicalParameters(String useCase, Long sourceEngineerId,
                                              Long sourceProjectId, List<MatchResultDto> results) {
        StringBuilder value = new StringBuilder(useCase.trim())
                .append("|sourceEngineer=").append(sourceEngineerId)
                .append("|sourceProject=").append(sourceProjectId);
        for (int i = 0; i < results.size(); i++) {
            MatchResultDto result = results.get(i);
            value.append("|result[").append(i).append("]=")
                    .append(result.getEngineerId()).append(',')
                    .append(result.getProjectId()).append(',')
                    .append(result.getBpAvailabilityId());
        }
        return value.toString();
    }

    private static BusinessException denied(String code) {
        return BusinessException.of(403, code);
    }

    private void validateTargetTenant(MatchResultDto dto, String tenantId) {
        if (dto == null) {
            throw com.ses.common.exception.BusinessException.of(400, "AI推薦候補が不正です");
        }
        validateTargetTenant(dto.getEngineerId(), dto.getProjectId(), tenantId);
        if (dto.getBpAvailabilityId() != null
                && bpAvailabilityMapper.selectByIdForTenant(dto.getBpAvailabilityId(), tenantId) == null) {
            throw com.ses.common.exception.BusinessException.of(403, "error.scope.notFound");
        }
    }

    private void validateTargetTenant(Long engineerId, Long projectId, String tenantId) {
        if (engineerId != null
                && tenantOwnershipResolver.selectEngineer(tenantId, engineerId) == null) {
            throw com.ses.common.exception.BusinessException.of(403, "error.scope.notFound");
        }
        if (projectId != null
                && projectMapper.selectByIdForTenant(projectId, tenantId) == null) {
            throw com.ses.common.exception.BusinessException.of(403, "error.scope.notFound");
        }
    }

    private Map<String, Object> engineerFields(String tenantId, Long engineerId) {
        if (engineerId == null) {
            return Map.of();
        }
        Engineer engineer = tenantOwnershipResolver.selectEngineer(tenantId, engineerId);
        if (engineer == null) {
            return Map.of();
        }
        return AiAllowlistFields.engineer(engineer,
                engineerSkillMapper.selectDetailByEngineerIdAndTenant(engineerId, tenantId));
    }

    private Map<String, Object> projectFields(String tenantId, Long projectId) {
        if (projectId == null) {
            return Map.of();
        }
        Project project = projectMapper.selectByIdForTenant(projectId, tenantId);
        if (project == null) {
            return Map.of();
        }
        return AiAllowlistFields.project(project);
    }

    private String toJson(Map<String, Object> masked) {
        try {
            return objectMapper.writeValueAsString(masked);
        } catch (Exception ex) {
            return "{}";
        }
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

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
