package com.ses.service.skillgap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.skillgap.AiCourseCandidateResult;
import com.ses.dto.skillgap.SkillGapItem;
import com.ses.dto.skillgap.SkillGapResult;
import com.ses.entity.LearningDecisionEvent;
import com.ses.entity.LearningCandidate;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.LearningCandidateMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.LearningDecisionEventMapper;
import com.ses.service.security.DataScopeService;
import com.ses.service.ai.AiExecutionGateway;
import com.ses.service.ai.AiGatewayRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** AIの成功・停止・timeout・errorをcandidateの状態へ閉じ込める。 */
@Service
public class AiLearningCandidateServiceImpl implements AiLearningCandidateService {

    private static final String USE_CASE = AiGatewayRequest.USE_LEARNING_CANDIDATE;

    private final AiExecutionGateway gateway;
    private final LearningDecisionEventMapper decisionEventMapper;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LearningCandidateMapper candidateMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiRecommendationRunMapper runMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataScopeService dataScopeService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProjectMapper projectMapper;

    public AiLearningCandidateServiceImpl(AiExecutionGateway gateway,
                                          LearningDecisionEventMapper decisionEventMapper,
                                          AiConfig aiConfig,
                                          ObjectMapper objectMapper,
                                          Clock clock) {
        this.gateway = gateway;
        this.decisionEventMapper = decisionEventMapper;
        this.aiConfig = aiConfig;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public AiCourseCandidateResult suggest(SkillGapResult ruleGap, List<Long> ruleBasedCourseIds,
                                           LocalDate asOf, Long actorUserId) {
        if (ruleGap == null || !"OK".equals(ruleGap.status())) {
            throw BusinessException.of(400, "skill.ai.ruleGapRequired");
        }
        LocalDate effectiveAsOf = asOf == null ? LocalDate.now(clock) : asOf;
        List<Long> ruleIds = normalizedIds(ruleBasedCourseIds);
        if (!aiConfig.isEnabled()) {
            return new AiCourseCandidateResult("RULE_ONLY", effectiveAsOf, ruleIds, List.of(), null, null,
                    "AI_DISABLED", true, false, null, ruleGap.snapshotId());
        }
        Map<String, Object> allowlist = new LinkedHashMap<>();
        allowlist.put("asOf", effectiveAsOf);
        allowlist.put("gapSkillIds", ruleGap.items().stream().filter(SkillGapItem::gap)
                .map(SkillGapItem::canonicalSkillId).filter(java.util.Objects::nonNull).toList());
        allowlist.put("ruleCourseIds", ruleIds);
        if (ruleGap.snapshotId() != null) {
            allowlist.put("ruleGapSnapshotId", ruleGap.snapshotId());
        }
        AiGatewayRequest request = AiGatewayRequest.builder()
                .useCase(USE_CASE)
                .trustedInstruction("不足skillを補うcourse候補IDだけをJSONで返す。評価、配置、採否を確定しない。")
                .allowlistedFields(allowlist)
                .persistRun(true)
                .actorUserId(actorUserId)
                .requireJson(true)
                .build();
        CompletableFuture<com.ses.service.ai.AiGatewayResult> future = CompletableFuture.supplyAsync(
                () -> gateway.execute(request));
        try {
            com.ses.service.ai.AiGatewayResult response = future.get(Math.max(1, aiConfig.getLearningCandidateTimeoutMs()),
                    TimeUnit.MILLISECONDS);
            if (response == null || response.getRunId() == null) {
                throw new IllegalStateException("AI run監査が保存されていません");
            }
            List<Long> aiIds = parseCourseIds(response.getText(), ruleIds);
            AiCourseCandidateResult result = new AiCourseCandidateResult("AI_CANDIDATE", effectiveAsOf, ruleIds, aiIds, response.getTraceId(),
                    response.getRunId(), null, true, true,
                    LocalDateTime.now(clock).plusMinutes(Math.max(1, aiConfig.getLearningCandidateTtlMinutes())),
                    ruleGap.snapshotId());
            persistCandidate(result, ruleGap);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            return fallback(ruleIds, effectiveAsOf, "TIMEOUT");
        } catch (Exception e) {
            return fallback(ruleIds, effectiveAsOf, "PROVIDER_ERROR");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void accept(AiCourseCandidateResult candidate, Long humanActorUserId, String reason) {
        recordHumanDecision(candidate, humanActorUserId, reason, "ACCEPT");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(AiCourseCandidateResult candidate, Long humanActorUserId, String reason) {
        recordHumanDecision(candidate, humanActorUserId, reason, "REJECT");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptCandidate(Long candidateId, Long humanActorUserId, String reason) {
        decidePersistedCandidate(candidateId, null, humanActorUserId, reason, "ACCEPT");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectCandidate(Long candidateId, Long humanActorUserId, String reason) {
        decidePersistedCandidate(candidateId, null, humanActorUserId, reason, "REJECT");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptCandidate(Long candidateId, Long expectedEngineerId, Long humanActorUserId, String reason) {
        decidePersistedCandidate(candidateId, expectedEngineerId, humanActorUserId, reason, "ACCEPT");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectCandidate(Long candidateId, Long expectedEngineerId, Long humanActorUserId, String reason) {
        decidePersistedCandidate(candidateId, expectedEngineerId, humanActorUserId, reason, "REJECT");
    }

    private void recordHumanDecision(AiCourseCandidateResult candidate, Long humanActorUserId,
                                     String reason, String decision) {
        if (candidate == null || humanActorUserId == null || reason == null || reason.isBlank()
                || !candidate.humanDecisionRequired() || !"AI_CANDIDATE".equals(candidate.status())
                || candidate.aiRunId() == null || candidate.expiresAt() == null) {
            throw BusinessException.of(400, "skill.ai.humanDecisionRequired");
        }
        if (!LocalDateTime.now(clock).isBefore(candidate.expiresAt())) {
            throw BusinessException.of(409, "skill.ai.candidateExpired");
        }
        String tenantId = currentTenant();
        if (candidateMapper != null) {
            LearningCandidate persisted = candidateMapper.selectByTenantId(tenantId, candidate.aiRunId());
            if (persisted == null || !java.util.Objects.equals(persisted.getSnapshotHash(), hash(candidate))) {
                throw BusinessException.of(403, "error.scope.notFound");
            }
            if (runMapper == null) {
                throw BusinessException.of(503, "skill.ai.candidateUnavailable");
            }
            AiRecommendationRun run = runMapper.selectById(candidate.aiRunId());
            if (run == null || !tenantId.equals(run.getTenantId()) || !USE_CASE.equals(run.getUseCase())) {
                throw BusinessException.of(403, "error.scope.notFound");
            }
        }
        LearningDecisionEvent event = new LearningDecisionEvent();
        event.setTenantId(tenantId);
        event.setDecisionDomain("LEARNING_SUGGESTION_" + decision);
        event.setSourceType("AI_COURSE_CANDIDATE");
        event.setSourceId(candidate.aiRunId());
        event.setHumanActorUserId(humanActorUserId);
        event.setAdverseUseFlag(0);
        event.setReason(reason.trim());
        event.setSnapshotHash(hash(candidate));
        event.setIdempotencyKey(event.getTenantId() + ":AI_COURSE_CANDIDATE:" + candidate.aiRunId() + ":" + decision);
        event.setOccurredAt(LocalDateTime.now(clock));
        event.setCreatedAt(event.getOccurredAt());
        decisionEventMapper.insertEvent(event);
    }

    private void persistCandidate(AiCourseCandidateResult result, SkillGapResult ruleGap) {
        if (candidateMapper == null || result.aiRunId() == null) {
            return;
        }
        LearningCandidate candidate = new LearningCandidate();
        candidate.setId(result.aiRunId());
        candidate.setTenantId(currentTenant());
        candidate.setEngineerId(ruleGap.engineerId());
        candidate.setProjectId(ruleGap.projectId());
        if (projectMapper != null) {
            com.ses.entity.Project project = projectMapper.selectByIdAndTenant(ruleGap.projectId(), candidate.getTenantId());
            candidate.setCustomerId(project == null ? null : project.getCustomerId());
        }
        candidate.setAsOfDate(result.asOf());
        candidate.setRuleGapSnapshotId(result.ruleGapSnapshotId());
        candidate.setRuleCourseIdsJson(json(result.courseIds()));
        candidate.setAiCourseIdsJson(json(result.aiSuggestedCourseIds()));
        candidate.setSnapshotHash(hash(result));
        candidate.setStatus("PENDING");
        candidate.setExpiresAt(result.expiresAt());
        candidate.setCreatedAt(LocalDateTime.now(clock));
        candidate.setUpdatedAt(candidate.getCreatedAt());
        candidate.setDeletedFlag(0);
        try {
            candidateMapper.insert(candidate);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            LearningCandidate existing = candidateMapper.selectByTenantId(candidate.getTenantId(), candidate.getId());
            if (existing == null || !existing.getSnapshotHash().equals(candidate.getSnapshotHash())) {
                throw BusinessException.of(409, "skill.ai.candidateConflict");
            }
        }
    }

    private void decidePersistedCandidate(Long candidateId, Long expectedEngineerId, Long actorUserId, String reason, String decision) {
        if (candidateMapper == null || candidateId == null || actorUserId == null
                || reason == null || reason.isBlank()) {
            throw BusinessException.of(400, "skill.ai.humanDecisionRequired");
        }
        String tenantId = currentTenant();
        LearningCandidate candidate = candidateMapper.selectByTenantId(tenantId, candidateId);
        if (candidate == null) {
            throw BusinessException.of(409, "skill.ai.candidateAlreadyDecided");
        }
        if (expectedEngineerId != null && !expectedEngineerId.equals(candidate.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (dataScopeService != null) {
            dataScopeService.assertAllowedEngineer(candidate.getEngineerId());
            dataScopeService.assertAllowedProject(candidate.getProjectId());
            if (candidate.getCustomerId() != null) {
                dataScopeService.assertAllowedCustomer(candidate.getCustomerId());
            }
        }
        if (runMapper == null) {
            throw BusinessException.of(503, "skill.ai.candidateUnavailable");
        }
        AiRecommendationRun run = runMapper.selectById(candidate.getId());
        if (run == null || !tenantId.equals(run.getTenantId()) || !"LEARNING_CANDIDATE".equals(run.getUseCase())) {
            throw BusinessException.of(403, "error.scope.notFound");
        }
        String expectedStatus = decision.equals("ACCEPT") ? "ACCEPTED" : "REJECTED";
        if (expectedStatus.equals(candidate.getStatus())) {
            // 同じ判断の再送は既存candidate/eventを正本とする（期限後も結果を反転させない）。
            return;
        }
        if (!"PENDING".equals(candidate.getStatus())) {
            throw BusinessException.of(409, "skill.ai.candidateAlreadyDecided");
        }
        if (candidate.getExpiresAt() == null || !LocalDateTime.now(clock).isBefore(candidate.getExpiresAt())) {
            throw BusinessException.of(409, "skill.ai.candidateExpired");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (candidateMapper.decide(tenantId, candidateId, expectedStatus,
                actorUserId, reason.trim(), now) != 1) {
            throw BusinessException.of(409, "skill.ai.candidateAlreadyDecided");
        }
        LearningDecisionEvent event = new LearningDecisionEvent();
        event.setTenantId(tenantId);
        event.setDecisionDomain("LEARNING_SUGGESTION_" + decision);
        event.setSourceType("AI_COURSE_CANDIDATE");
        event.setSourceId(candidateId);
        event.setHumanActorUserId(actorUserId);
        event.setAdverseUseFlag(0);
        event.setReason(reason.trim());
        event.setSnapshotHash(candidate.getSnapshotHash());
        event.setIdempotencyKey(tenantId + ":AI_COURSE_CANDIDATE:" + candidateId + ":" + decision);
        event.setOccurredAt(now);
        event.setCreatedAt(now);
        try {
            decisionEventMapper.insertEvent(event);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 同じ判断の再送は既存状態を正本とする。
        }
    }

    private String currentTenant() {
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.getExplicitTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw BusinessException.of(403, "error.tenant.contextRequired");
        }
        return tenantId;
    }

    private String json(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? List.of() : ids);
        } catch (Exception e) {
            throw new IllegalStateException("AI candidate JSONを生成できません", e);
        }
    }

    private AiCourseCandidateResult fallback(List<Long> ruleIds, LocalDate asOf, String errorCode) {
        return new AiCourseCandidateResult("DEGRADED", asOf, ruleIds, List.of(), null, null, errorCode, true, false,
                null, null);
    }

    private List<Long> parseCourseIds(String text, List<Long> allowlistedIds) throws Exception {
        JsonNode root = objectMapper.readTree(text == null ? "{}" : text);
        JsonNode ids = root.isArray() ? root : root.path("courseIds");
        if (!ids.isArray()) {
            throw new IllegalArgumentException("courseIdsがありません");
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (JsonNode id : ids) {
            if (id.canConvertToLong() && allowlistedIds.contains(id.longValue())) {
                result.add(id.longValue());
            }
        }
        return List.copyOf(result);
    }

    private List<Long> normalizedIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(ids.stream().filter(java.util.Objects::nonNull).limit(20).toList()));
    }

    private String hash(AiCourseCandidateResult candidate) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(candidate));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("AI candidate hashを生成できません", e);
        }
    }
}
