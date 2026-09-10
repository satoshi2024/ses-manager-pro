package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.project.ProjectSkillDetailDto;
import com.ses.dto.skill.SkillReplaceRequest;
import com.ses.entity.ProjectSkill;
import com.ses.entity.ProjectSkillEvent;
import com.ses.mapper.ProjectSkillEventMapper;
import com.ses.service.ProjectSkillService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.effective.EffectiveIntervalSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class ProjectSkillServiceImpl extends ServiceImpl<com.ses.mapper.ProjectSkillMapper, ProjectSkill> implements ProjectSkillService {

    private final com.ses.mapper.ProjectMapper projectMapper;
    private final com.ses.mapper.SkillTagMapper skillTagMapper;
    private final ProjectSkillEventMapper projectSkillEventMapper;
    private final java.time.Clock clock;

    public ProjectSkillServiceImpl(com.ses.mapper.ProjectMapper projectMapper,
                                   com.ses.mapper.SkillTagMapper skillTagMapper,
                                   ProjectSkillEventMapper projectSkillEventMapper,
                                   java.time.Clock clock) {
        this.projectMapper = projectMapper;
        this.skillTagMapper = skillTagMapper;
        this.projectSkillEventMapper = projectSkillEventMapper;
        this.clock = clock;
    }

    @Override
    public List<ProjectSkillDetailDto> listDetail(Long projectId) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        return baseMapper.selectDetailByProjectIdForTenant(projectId, tenantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceSkills(Long projectId, List<ProjectSkill> skills) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        com.ses.entity.Project parent = projectMapper.selectByIdForTenant(projectId, tenantId);
        if (parent == null || parent.getVersion() == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.project.notFound");
        }
        SkillReplaceRequest request = new SkillReplaceRequest();
        request.setExpectedVersion(parent.getVersion());
        request.setReason("内部skill projection更新");
        request.setSkills(skills == null ? List.of() : skills.stream().map(this::toItem).toList());
        replaceSkills(projectId, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceSkills(Long projectId, SkillReplaceRequest request) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        if (request == null || request.getExpectedVersion() == null || request.getReason() == null
                || request.getReason().isBlank() || request.getSkills() == null) {
            throw com.ses.common.exception.BusinessException.of(400, "error.skill.replaceRequestRequired");
        }
        com.ses.entity.Project parent = projectMapper.selectByIdForUpdateForTenant(projectId, tenantId);
        if (parent == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.project.notFound");
        }
        if (parent.getVersion() == null || !request.getExpectedVersion().equals(parent.getVersion())) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }

        List<ProjectSkill> skills = request.getSkills().stream().map(item -> {
            ProjectSkill skill = new ProjectSkill();
            skill.setProjectId(projectId);
            skill.setSkillId(item.getSkillId());
            skill.setRequiredLevel(item.getRequiredLevel());
            skill.setIsMust(item.getIsMust());
            return skill;
        }).toList();
        if (!skills.isEmpty()) {
            if (skills.stream().anyMatch(s -> s.getSkillId() == null)) {
                throw com.ses.common.exception.BusinessException.of(400, "error.skill.notFound");
            }
            List<Long> skillIds = skills.stream()
                    .map(ProjectSkill::getSkillId)
                    .distinct()
                    .collect(Collectors.toList());
            List<com.ses.entity.SkillTag> existingSkills = skillTagMapper.selectBatchIds(skillIds);
            if (existingSkills.size() != skillIds.size()) {
                throw com.ses.common.exception.BusinessException.of(400, "error.skill.notFound");
            }
        }

        LocalDate effectiveDate = LocalDate.now(clock);
        LocalDateTime occurredAt = LocalDateTime.now(clock);
        Long actorUserId = SecurityUtils.currentUserId();
        String actorRole = SecurityUtils.currentRole();

        List<ProjectSkill> existing = baseMapper.selectByProjectIdForTenant(projectId, tenantId);
        Map<Long, Long> supersedesBySkillId = new HashMap<>();
        for (ProjectSkill skill : existing) {
            Long closedEventId = closeOpenSkillEvent(tenantId, projectId, skill.getSkillId(), effectiveDate);
            if (closedEventId != null) {
                supersedesBySkillId.put(skill.getSkillId(), closedEventId);
            }
        }

        baseMapper.deleteByProjectIdForTenant(projectId, tenantId);

        List<ProjectSkill> distinctSkills = skills.stream()
                .filter(distinctByKey(ProjectSkill::getSkillId))
                .peek(skill -> skill.setProjectId(projectId))
                .collect(Collectors.toList());

        for (ProjectSkill skill : distinctSkills) {
            if (baseMapper.insertForTenant(skill, tenantId) != 1) {
                throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
            }
        }

        for (ProjectSkill skill : distinctSkills) {
            assertNoOpenSkillEvent(tenantId, projectId, skill.getSkillId());
            Long supersedesId = resolveSupersedesEventId(tenantId, projectId, skill.getSkillId(), supersedesBySkillId);
            appendSkillEvent(skill, ProjectSkillEvent.TYPE_OPEN, effectiveDate, null,
                    supersedesId, actorUserId, actorRole, occurredAt, tenantId, request.getReason().trim());
        }
        if (projectMapper.bumpVersionForTenant(projectId, tenantId, request.getExpectedVersion()) != 1) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    private Long resolveSupersedesEventId(String tenantId, Long projectId, Long skillId,
                                          Map<Long, Long> closedInTx) {
        Long supersedesId = closedInTx.get(skillId);
        if (supersedesId != null) {
            return supersedesId;
        }
        ProjectSkillEvent lastClosed = projectSkillEventMapper.selectLastClosedOpenEventForTenant(
                tenantId, projectId, skillId);
        return lastClosed != null ? lastClosed.getId() : null;
    }

    private Long closeOpenSkillEvent(String tenantId, Long projectId, Long skillId, LocalDate changeDate) {
        ProjectSkillEvent open = projectSkillEventMapper.selectOpenEventForTenant(tenantId, projectId, skillId);
        if (open == null) {
            return null;
        }
        LocalDate closeTo = EffectiveIntervalSupport.closeEffectiveTo(open.getEffectiveFrom(), changeDate);
        int rows = projectSkillEventMapper.closeOpenEventForTenant(open.getId(), closeTo, tenantId);
        if (rows != 1) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }
        return open.getId();
    }

    private void assertNoOpenSkillEvent(String tenantId, Long projectId, Long skillId) {
        if (projectSkillEventMapper.selectOpenEventForTenant(tenantId, projectId, skillId) != null) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    private void appendSkillEvent(ProjectSkill skill, String eventType, LocalDate effectiveFrom,
                                  LocalDate effectiveTo, Long supersedesEventId, Long actorUserId, String actorRole,
                                  LocalDateTime occurredAt, String tenantId, String reason) {
        ProjectSkillEvent event = new ProjectSkillEvent();
        event.setTenantId(tenantId);
        event.setProjectId(skill.getProjectId());
        event.setProjectSkillId(skill.getId());
        event.setSkillId(skill.getSkillId());
        event.setRequiredLevel(skill.getRequiredLevel());
        event.setIsMust(skill.getIsMust());
        event.setEventType(eventType);
        event.setEffectiveFrom(effectiveFrom);
        event.setEffectiveTo(effectiveTo);
        event.setSupersedesEventId(supersedesEventId);
        event.setActorUserId(actorUserId);
        event.setActorRoleSnapshot(actorRole);
        event.setReason(reason);
        event.setOccurredAt(occurredAt);
        event.setCreatedAt(occurredAt);
        projectSkillEventMapper.insertEvent(event);
    }

    private SkillReplaceRequest.SkillItem toItem(ProjectSkill skill) {
        SkillReplaceRequest.SkillItem item = new SkillReplaceRequest.SkillItem();
        item.setSkillId(skill.getSkillId());
        item.setRequiredLevel(skill.getRequiredLevel());
        item.setIsMust(skill.getIsMust());
        return item;
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }
}
