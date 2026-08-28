package com.ses.service.skillgap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.skillgap.AiCourseCandidateResult;
import com.ses.dto.skillgap.SkillGapItem;
import com.ses.dto.skillgap.SkillGapResult;
import com.ses.entity.LearningDecisionEvent;
import com.ses.mapper.LearningDecisionEventMapper;
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
            return new AiCourseCandidateResult("AI_CANDIDATE", effectiveAsOf, ruleIds, aiIds, response.getTraceId(),
                    response.getRunId(), null, true, true,
                    LocalDateTime.now(clock).plusMinutes(Math.max(1, aiConfig.getLearningCandidateTtlMinutes())),
                    ruleGap.snapshotId());
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
        LearningDecisionEvent event = new LearningDecisionEvent();
        event.setTenantId("default");
        event.setDecisionDomain("LEARNING_SUGGESTION_" + decision);
        event.setSourceType("AI_COURSE_CANDIDATE");
        event.setSourceId(candidate.aiRunId());
        event.setHumanActorUserId(humanActorUserId);
        event.setAdverseUseFlag(0);
        event.setReason(reason.trim());
        event.setSnapshotHash(hash(candidate));
        event.setOccurredAt(LocalDateTime.now(clock));
        event.setCreatedAt(event.getOccurredAt());
        decisionEventMapper.insertEvent(event);
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
