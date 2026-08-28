package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.EngineerSkill;
import com.ses.entity.EngineerSkillEvent;
import com.ses.mapper.EngineerSkillEventMapper;
import com.ses.service.EngineerSkillService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class EngineerSkillServiceImpl extends ServiceImpl<com.ses.mapper.EngineerSkillMapper, EngineerSkill> implements EngineerSkillService {

    private static final String DEFAULT_TENANT = "default";

    private final com.ses.mapper.EngineerMapper engineerMapper;
    private final com.ses.mapper.SkillTagMapper skillTagMapper;
    private final EngineerSkillEventMapper engineerSkillEventMapper;
    private final java.time.Clock clock;

    public EngineerSkillServiceImpl(com.ses.mapper.EngineerMapper engineerMapper,
                                    com.ses.mapper.SkillTagMapper skillTagMapper,
                                    EngineerSkillEventMapper engineerSkillEventMapper,
                                    java.time.Clock clock) {
        this.engineerMapper = engineerMapper;
        this.skillTagMapper = skillTagMapper;
        this.engineerSkillEventMapper = engineerSkillEventMapper;
        this.clock = clock;
    }

    @Override
    public List<EngineerSkillDetailDto> listDetail(Long engineerId) {
        return baseMapper.selectDetailByEngineerId(engineerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceSkills(Long engineerId, List<EngineerSkill> skills) {
        if (engineerMapper.selectById(engineerId) == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.engineer.notFound");
        }

        if (skills != null && !skills.isEmpty()) {
            if (skills.stream().anyMatch(s -> s.getSkillId() == null)) {
                throw com.ses.common.exception.BusinessException.of(400, "error.skill.notFound");
            }
            List<Long> skillIds = skills.stream()
                    .map(EngineerSkill::getSkillId)
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

        List<EngineerSkill> existing = list(new LambdaQueryWrapper<EngineerSkill>()
                .eq(EngineerSkill::getEngineerId, engineerId));
        for (EngineerSkill skill : existing) {
            appendSkillEvent(skill, EngineerSkillEvent.TYPE_CLOSE, effectiveDate, effectiveDate,
                    actorUserId, actorRole, occurredAt);
        }

        remove(new LambdaQueryWrapper<EngineerSkill>().eq(EngineerSkill::getEngineerId, engineerId));

        if (skills == null || skills.isEmpty()) {
            return;
        }

        List<EngineerSkill> distinctSkills = skills.stream()
                .filter(distinctByKey(EngineerSkill::getSkillId))
                .peek(skill -> skill.setEngineerId(engineerId))
                .collect(Collectors.toList());

        saveBatch(distinctSkills);

        for (EngineerSkill skill : distinctSkills) {
            appendSkillEvent(skill, EngineerSkillEvent.TYPE_OPEN, effectiveDate, null,
                    actorUserId, actorRole, occurredAt);
        }
    }

    private void appendSkillEvent(EngineerSkill skill, String eventType, LocalDate effectiveFrom,
                                  LocalDate effectiveTo, Long actorUserId, String actorRole,
                                  LocalDateTime occurredAt) {
        EngineerSkillEvent event = new EngineerSkillEvent();
        event.setTenantId(DEFAULT_TENANT);
        event.setEngineerId(skill.getEngineerId());
        event.setEngineerSkillId(skill.getId());
        event.setSkillId(skill.getSkillId());
        event.setProficiency(skill.getProficiency());
        event.setExperienceYears(skill.getExperienceYears());
        event.setEventType(eventType);
        event.setEffectiveFrom(effectiveFrom);
        event.setEffectiveTo(effectiveTo);
        event.setActorUserId(actorUserId);
        event.setActorRoleSnapshot(actorRole);
        event.setOccurredAt(occurredAt);
        event.setCreatedAt(occurredAt);
        engineerSkillEventMapper.insertEvent(event);
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }
}
