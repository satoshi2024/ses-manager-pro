package com.ses.service.skillgap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.ses.mapper.LearningDecisionEventMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.EngineerSkillService;
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

    private static final String TENANT = "default";
    private static final List<String> LEVELS = List.of("初級", "中級", "上級");

    private final EngineerSkillAssessmentMapper assessmentMapper;
    private final LearningDecisionEventMapper decisionEventMapper;
    private final EngineerAccountLinkMapper accountLinkMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final SysUserMapper sysUserMapper;
    private final EngineerSkillService engineerSkillService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public SkillAssessmentServiceImpl(EngineerSkillAssessmentMapper assessmentMapper,
                                      LearningDecisionEventMapper decisionEventMapper,
                                      EngineerAccountLinkMapper accountLinkMapper,
                                      UserOrganizationMapper userOrganizationMapper,
                                      SysUserMapper sysUserMapper,
                                      EngineerSkillService engineerSkillService,
                                      Clock clock,
                                      ObjectMapper objectMapper) {
        this.assessmentMapper = assessmentMapper;
        this.decisionEventMapper = decisionEventMapper;
        this.accountLinkMapper = accountLinkMapper;
        this.userOrganizationMapper = userOrganizationMapper;
        this.sysUserMapper = sysUserMapper;
        this.engineerSkillService = engineerSkillService;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerSkillAssessment submitSelf(Long engineerId, Long skillId, String proposedLevel,
                                              LocalDate effectiveFrom, Long actorUserId, String reason) {
        requireActor(actorUserId, reason);
        EngineerAccountLink link = accountLinkMapper.selectByEngineerId(engineerId);
        if (link == null || !actorUserId.equals(link.getSysUserId()) || !activeUser(actorUserId)) {
            throw BusinessException.of(403, "skill.assessment.selfOnly");
        }
        return save(EngineerSkillAssessment.TYPE_SELF, engineerId, skillId, proposedLevel,
                effectiveFrom, actorUserId, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerSkillAssessment submitManager(Long engineerId, Long skillId, String proposedLevel,
                                                 LocalDate effectiveFrom, Long actorUserId, String reason) {
        requireActor(actorUserId, reason);
        SysUser actor = activeUserEntity(actorUserId);
        if (actor == null || !"マネージャー".equals(actor.getRole())) {
            throw BusinessException.of(403, "skill.assessment.managerOnly");
        }
        EngineerAccountLink link = accountLinkMapper.selectByEngineerId(engineerId);
        if (link == null || !isCurrentManager(link.getSysUserId(), actorUserId, effectiveDate(effectiveFrom))) {
            throw BusinessException.of(403, "skill.assessment.managerOutOfScope");
        }
        return save(EngineerSkillAssessment.TYPE_MANAGER, engineerId, skillId, proposedLevel,
                effectiveFrom, actorUserId, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EngineerSkillAssessment finalizeByHr(Long engineerId, Long skillId, String proposedLevel,
                                                LocalDate effectiveFrom, Long actorUserId, String reason) {
        requireActor(actorUserId, reason);
        SysUser actor = activeUserEntity(actorUserId);
        if (actor == null || !("HR".equals(actor.getRole()) || "管理者".equals(actor.getRole()))) {
            throw BusinessException.of(403, "skill.assessment.hrOnly");
        }
        EngineerSkillAssessment assessment = save(EngineerSkillAssessment.TYPE_HR_FINAL, engineerId, skillId,
                proposedLevel, effectiveFrom, actorUserId, reason);

        List<EngineerSkill> current = new ArrayList<>(engineerSkillService.list(
                new LambdaQueryWrapper<EngineerSkill>().eq(EngineerSkill::getEngineerId, engineerId)));
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
        engineerSkillService.replaceSkills(engineerId, current);
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
        assessment.setTenantId(TENANT);
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

    private boolean isCurrentManager(Long userId, Long managerId, LocalDate asOf) {
        return userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                        .eq(UserOrganization::getUserId, userId)
                        .eq(UserOrganization::getManagerUserId, managerId)
                        .eq(UserOrganization::getPrimaryFlag, 1)
                        .eq(UserOrganization::getDeletedFlag, 0)
                        .le(UserOrganization::getValidFrom, asOf)
                        .and(wrapper -> wrapper.isNull(UserOrganization::getValidTo)
                                .or().ge(UserOrganization::getValidTo, asOf)))
                .stream().findAny().isPresent();
    }

    private SysUser activeUserEntity(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        return user != null && Integer.valueOf(1).equals(user.getStatus()) ? user : null;
    }

    private boolean activeUser(Long userId) {
        return activeUserEntity(userId) != null;
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
        event.setTenantId(TENANT);
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
