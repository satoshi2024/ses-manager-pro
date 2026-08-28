package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.project.ProjectSkillDetailDto;
import com.ses.entity.ProjectSkill;
import com.ses.entity.ProjectSkillEvent;
import com.ses.mapper.ProjectSkillEventMapper;
import com.ses.service.ProjectSkillService;
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

    private static final String DEFAULT_TENANT = "default";

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
        return baseMapper.selectDetailByProjectId(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceSkills(Long projectId, List<ProjectSkill> skills) {
        if (projectMapper.selectById(projectId) == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.project.notFound");
        }

        if (skills != null && !skills.isEmpty()) {
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

        List<ProjectSkill> existing = list(new LambdaQueryWrapper<ProjectSkill>()
                .eq(ProjectSkill::getProjectId, projectId));
        Map<Long, Long> supersedesBySkillId = new HashMap<>();
        for (ProjectSkill skill : existing) {
            Long closedEventId = closeOpenSkillEvent(projectId, skill.getSkillId(), effectiveDate);
            if (closedEventId != null) {
                supersedesBySkillId.put(skill.getSkillId(), closedEventId);
            }
        }

        remove(new LambdaQueryWrapper<ProjectSkill>().eq(ProjectSkill::getProjectId, projectId));

        if (skills == null || skills.isEmpty()) {
            return;
        }

        List<ProjectSkill> distinctSkills = skills.stream()
                .filter(distinctByKey(ProjectSkill::getSkillId))
                .peek(skill -> skill.setProjectId(projectId))
                .collect(Collectors.toList());

        saveBatch(distinctSkills);

        for (ProjectSkill skill : distinctSkills) {
            assertNoOpenSkillEvent(projectId, skill.getSkillId());
            Long supersedesId = resolveSupersedesEventId(projectId, skill.getSkillId(), supersedesBySkillId);
            appendSkillEvent(skill, ProjectSkillEvent.TYPE_OPEN, effectiveDate, null,
                    supersedesId, actorUserId, actorRole, occurredAt);
        }
    }

    private Long resolveSupersedesEventId(Long projectId, Long skillId, Map<Long, Long> closedInTx) {
        Long supersedesId = closedInTx.get(skillId);
        if (supersedesId != null) {
            return supersedesId;
        }
        ProjectSkillEvent lastClosed = projectSkillEventMapper.selectLastClosedOpenEvent(projectId, skillId);
        return lastClosed != null ? lastClosed.getId() : null;
    }

    private Long closeOpenSkillEvent(Long projectId, Long skillId, LocalDate changeDate) {
        ProjectSkillEvent open = projectSkillEventMapper.selectOpenEvent(projectId, skillId);
        if (open == null) {
            return null;
        }
        LocalDate closeTo = EffectiveIntervalSupport.closeEffectiveTo(open.getEffectiveFrom(), changeDate);
        int rows = projectSkillEventMapper.closeOpenEvent(open.getId(), closeTo);
        if (rows != 1) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }
        return open.getId();
    }

    private void assertNoOpenSkillEvent(Long projectId, Long skillId) {
        if (projectSkillEventMapper.selectOpenEvent(projectId, skillId) != null) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    private void appendSkillEvent(ProjectSkill skill, String eventType, LocalDate effectiveFrom,
                                  LocalDate effectiveTo, Long supersedesEventId, Long actorUserId, String actorRole,
                                  LocalDateTime occurredAt) {
        ProjectSkillEvent event = new ProjectSkillEvent();
        event.setTenantId(DEFAULT_TENANT);
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
        event.setOccurredAt(occurredAt);
        event.setCreatedAt(occurredAt);
        projectSkillEventMapper.insertEvent(event);
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }
}
