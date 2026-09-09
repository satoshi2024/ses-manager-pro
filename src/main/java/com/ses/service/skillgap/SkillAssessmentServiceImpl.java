package com.ses.service.skillgap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSkill;
import com.ses.entity.EngineerSkillAssessment;
import com.ses.entity.LearningDecisionEvent;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerSkillAssessmentMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LearningDecisionEventMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.EngineerSkillService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.TenantOwnershipResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * skill assessmentの人による確定境界。
 * SELF/MANAGERはproposalとして保存し、t_engineer_skillを変更できるのはHR_FINALだけ。
 */
@Service
public class SkillAssessmentServiceImpl implements SkillAssessmentService {

    private static final List<String> LEVELS = List.of("初級", "中級", "上級");

    private final EngineerSkillAssessmentMapper assessmentMapper;
    private final LearningDecisionEventMapper decisionEventMapper;
    private final EngineerAccountLinkMapper accountLinkMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final SysUserMapper sysUserMapper;
    private final EngineerSkillService engineerSkillService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TenantOwnershipResolver tenantOwnershipResolver;
    private final EngineerMapper engineerMapper;

    public SkillAssessmentServiceImpl(EngineerSkillAssessmentMapper assessmentMapper,
                                      LearningDecisionEventMapper decisionEventMapper,
                                      EngineerAccountLinkMapper accountLinkMapper,
                                      UserOrganizationMapper userOrganizationMapper,
                                      SysUserMapper sysUserMapper,
                                      EngineerSkillService engineerSkillService,
                                      Clock clock,
                                      ObjectMapper objectMapper,
                                      TenantOwnershipResolver tenantOwnershipResolver,
                                      EngineerMapper engineerMapper) {
        this.assessmentMapper = assessmentMapper;
        this.decisionEventMapper = decisionEventMapper;
        this.accountLinkMapper = accountLinkMapper;
        this.userOrganizationMapper = userOrganizationMapper;
        this.sysUserMapper = sysUserMapper;
        this.engineerSkillService = engineerSkillService;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.tenantOwnershipResolver = tenantOwnershipResolver;
        this.engineerMapper = engineerMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerSkillAssessment submitSelf(Long engineerId, Long skillId, String proposedLevel,
                                               LocalDate effectiveFrom, Long actorUserId, String reason) {
        String tenantId = currentTenant();
        requireActor(actorUserId, reason);
        requireEngineer(tenantId, engineerId);
        EngineerAccountLink link = accountLinkMapper.selectByEngineerIdAndTenant(engineerId, tenantId);
        if (link == null || !actorUserId.equals(link.getSysUserId()) || !activeUser(actorUserId, tenantId)) {
            throw BusinessException.of(403, "skill.assessment.selfOnly");
        }
        return save(EngineerSkillAssessment.TYPE_SELF, engineerId, skillId, proposedLevel,
                effectiveFrom, actorUserId, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerSkillAssessment submitManager(Long engineerId, Long skillId, String proposedLevel,
                                                 LocalDate effectiveFrom, Long actorUserId, String reason) {
        String tenantId = currentTenant();
        requireActor(actorUserId, reason);
        requireEngineer(tenantId, engineerId);
        SysUser actor = activeUserEntity(actorUserId, tenantId);
        if (actor == null || !"マネージャー".equals(actor.getRole())) {
            throw BusinessException.of(403, "skill.assessment.managerOnly");
        }
        EngineerAccountLink link = accountLinkMapper.selectByEngineerIdAndTenant(engineerId, tenantId);
        if (link == null || !isCurrentManager(tenantId, link.getSysUserId(), actorUserId, effectiveDate(effectiveFrom))) {
            throw BusinessException.of(403, "skill.assessment.managerOutOfScope");
        }
        return save(EngineerSkillAssessment.TYPE_MANAGER, engineerId, skillId, proposedLevel,
                effectiveFrom, actorUserId, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerSkillAssessment finalizeByHr(Long engineerId, Long skillId, String proposedLevel,
                                                LocalDate effectiveFrom, Long actorUserId, String reason) {
        String tenantId = currentTenant();
        requireActor(actorUserId, reason);
        requireEngineer(tenantId, engineerId);
        SysUser actor = activeUserEntity(actorUserId, tenantId);
        if (actor == null || !("HR".equals(actor.getRole()) || "管理者".equals(actor.getRole()))) {
            throw BusinessException.of(403, "skill.assessment.hrOnly");
        }
        EngineerSkillAssessment assessment = save(EngineerSkillAssessment.TYPE_HR_FINAL, engineerId, skillId,
                proposedLevel, effectiveFrom, actorUserId, reason);

        List<EngineerSkill> current = new ArrayList<>(engineerSkillService.listForTenant(engineerId));
        boolean found = false;
        for (EngineerSkill skill : current) {
            if (skillId.equals(skill.getSkillId())) {
                skill.setProficiency(proposedLevel);
                found = true;
                break;
            }
        }
        if (!found) {
            EngineerSkill skill = new EngineerSkill();
            skill.setEngineerId(engineerId);
            skill.setSkillId(skillId);
            skill.setProficiency(proposedLevel);
            current.add(skill);
        }
        // 共通serviceがcurrent projectionとeffective eventを同一transactionで更新する。
        com.ses.entity.Engineer parent = engineerMapper.selectByIdForTenant(engineerId, tenantId);
        if (parent == null || parent.getVersion() == null) {
            throw BusinessException.of(404, "error.engineer.notFound");
        }
        com.ses.dto.skill.SkillReplaceRequest request = new com.ses.dto.skill.SkillReplaceRequest();
        request.setExpectedVersion(parent.getVersion());
        request.setReason("HR資格評価確定");
        request.setSkills(current.stream().map(skill -> {
            com.ses.dto.skill.SkillReplaceRequest.SkillItem item =
                    new com.ses.dto.skill.SkillReplaceRequest.SkillItem();
            item.setSkillId(skill.getSkillId());
            item.setProficiency(skill.getProficiency());
            item.setExperienceYears(skill.getExperienceYears());
            return item;
        }).toList());
        engineerSkillService.replaceSkills(engineerId, request);
        appendDecision("SKILL_LEVEL", assessment.getId(), actorUserId, reason,
                snapshotHash(assessment), 0);
        return assessment;
    }

    private EngineerSkillAssessment save(String type, Long engineerId, Long skillId, String level,
                                         LocalDate effectiveFrom, Long actorUserId, String reason) {
        if (engineerId == null || skillId == null || !LEVELS.contains(level)) {
            throw BusinessException.of(400, "skill.assessment.invalid");
        }
        LocalDate from = effectiveFrom == null ? LocalDate.now(clock) : effectiveFrom;
        EngineerSkillAssessment assessment = new EngineerSkillAssessment();
        assessment.setTenantId(currentTenant());
        assessment.setEngineerId(engineerId);
        assessment.setSkillId(skillId);
        assessment.setAssessmentType(type);
        assessment.setProposedLevel(level);
        assessment.setAssessmentState(EngineerSkillAssessment.TYPE_HR_FINAL.equals(type) ? "FINAL" : "PROPOSED");
        assessment.setEffectiveFrom(from);
        assessment.setActorUserId(actorUserId);
        assessment.setReason(reason.trim());
        assessment.setVersion(0);
        assessmentMapper.insert(assessment);
        if (!EngineerSkillAssessment.TYPE_HR_FINAL.equals(type)) {
            appendDecision("SKILL_ASSESSMENT_PROPOSAL", assessment.getId(), actorUserId,
                    reason, snapshotHash(assessment), 0);
        }
        return assessment;
    }

    private boolean isCurrentManager(String tenantId, Long userId, Long managerId, LocalDate asOf) {
        return !userOrganizationMapper.selectManagerAssignmentByTenant(tenantId, userId, managerId, asOf).isEmpty();
    }

    private SysUser activeUserEntity(Long userId, String tenantId) {
        SysUser user = sysUserMapper.selectByIdAndTenant(userId, tenantId);
        return user != null && Integer.valueOf(1).equals(user.getStatus()) ? user : null;
    }

    private boolean activeUser(Long userId, String tenantId) {
        return activeUserEntity(userId, tenantId) != null;
    }

    private LocalDate effectiveDate(LocalDate date) {
        return date == null ? LocalDate.now(clock) : date;
    }

    private void requireActor(Long actorUserId, String reason) {
        if (actorUserId == null || reason == null || reason.isBlank()) {
            throw BusinessException.of(400, "skill.assessment.actorReasonRequired");
        }
    }

    private void appendDecision(String domain, Long sourceId, Long actorUserId, String reason,
                                String hash, int adverseUseFlag) {
        LearningDecisionEvent event = new LearningDecisionEvent();
        event.setTenantId(currentTenant());
        event.setDecisionDomain(domain);
        event.setSourceType("ENGINEER_SKILL_ASSESSMENT");
        event.setSourceId(sourceId);
        event.setHumanActorUserId(actorUserId);
        event.setAdverseUseFlag(adverseUseFlag);
        event.setReason(reason.trim());
        event.setSnapshotHash(hash);
        event.setOccurredAt(LocalDateTime.now(clock));
        event.setCreatedAt(event.getOccurredAt());
        decisionEventMapper.insertEvent(event);
    }

    private String currentTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }

    private void requireEngineer(String tenantId, Long engineerId) {
        if (engineerId == null || tenantOwnershipResolver.selectEngineer(tenantId, engineerId) == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    private String snapshotHash(EngineerSkillAssessment assessment) {
        try {
            String value = objectMapper.writeValueAsString(List.of(assessment.getEngineerId(), assessment.getSkillId(),
                    assessment.getAssessmentType(), assessment.getProposedLevel(), assessment.getEffectiveFrom(),
                    assessment.getActorUserId(), assessment.getReason()));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("assessment snapshot hashを生成できません", e);
        }
    }
}
