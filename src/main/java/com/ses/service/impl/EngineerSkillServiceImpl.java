package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.dto.skill.SkillReplaceRequest;
import com.ses.entity.EngineerSkill;
import com.ses.entity.EngineerSkillEvent;
import com.ses.mapper.EngineerSkillEventMapper;
import com.ses.service.EngineerSkillService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.effective.EffectiveIntervalSupport;
import com.ses.service.security.TenantOwnershipResolver;
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
public class EngineerSkillServiceImpl extends ServiceImpl<com.ses.mapper.EngineerSkillMapper, EngineerSkill> implements EngineerSkillService {

    private final com.ses.mapper.EngineerMapper engineerMapper;
    private final com.ses.mapper.SkillTagMapper skillTagMapper;
    private final EngineerSkillEventMapper engineerSkillEventMapper;
    private final TenantOwnershipResolver tenantOwnershipResolver;
    private final java.time.Clock clock;

    public EngineerSkillServiceImpl(com.ses.mapper.EngineerMapper engineerMapper,
                                    com.ses.mapper.SkillTagMapper skillTagMapper,
                                    EngineerSkillEventMapper engineerSkillEventMapper,
                                    java.time.Clock clock,
                                    TenantOwnershipResolver tenantOwnershipResolver) {
        this.engineerMapper = engineerMapper;
        this.skillTagMapper = skillTagMapper;
        this.engineerSkillEventMapper = engineerSkillEventMapper;
        this.clock = clock;
        this.tenantOwnershipResolver = tenantOwnershipResolver;
    }

    @Override
    public List<EngineerSkillDetailDto> listDetail(Long engineerId) {
        String tenantId = requireTenant();
        if (tenantOwnershipResolver.selectEngineer(tenantId, engineerId) == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        return baseMapper.selectDetailByEngineerIdAndTenant(engineerId, tenantId);
    }

    @Override
    public List<EngineerSkill> listForTenant(Long engineerId) {
        String tenantId = requireTenant();
        if (tenantOwnershipResolver.selectEngineer(tenantId, engineerId) == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        return baseMapper.selectListByEngineerIdAndTenant(engineerId, tenantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceSkills(Long engineerId, List<EngineerSkill> skills) {
        String tenantId = requireTenant();
        com.ses.entity.Engineer parent = engineerMapper.selectByIdForTenant(engineerId, tenantId);
        if (parent == null || parent.getVersion() == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.engineer.notFound");
        }
        SkillReplaceRequest request = new SkillReplaceRequest();
        request.setExpectedVersion(parent.getVersion());
        request.setReason("内部skill projection更新");
        request.setSkills(skills == null ? List.of() : skills.stream().map(this::toItem).toList());
        replaceSkills(engineerId, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceSkills(Long engineerId, SkillReplaceRequest request) {
        String tenantId = requireTenant();
        String role = SecurityUtils.currentRole();
        if (role != null && !"HR".equals(role) && !"管理者".equals(role) && !"SYSTEM".equals(role)) {
            // SELF/MANAGER/AIはassessment proposalだけを作成し、公式projectionを直接変更しない。
            throw com.ses.common.exception.BusinessException.of(403, "error.skill.officialProjectionHrOnly");
        }
        if (request == null || request.getExpectedVersion() == null || request.getReason() == null
                || request.getReason().isBlank() || request.getSkills() == null) {
            throw com.ses.common.exception.BusinessException.of(400, "error.skill.replaceRequestRequired");
        }
        com.ses.entity.Engineer parent = engineerMapper.selectByIdForUpdateForTenant(engineerId, tenantId);
        if (parent == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.engineer.notFound");
        }
        if (parent.getVersion() == null || !request.getExpectedVersion().equals(parent.getVersion())) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }

        List<EngineerSkill> skills = request.getSkills().stream().map(item -> {
            EngineerSkill skill = new EngineerSkill();
            skill.setEngineerId(engineerId);
            skill.setSkillId(item.getSkillId());
            skill.setProficiency(item.getProficiency());
            skill.setExperienceYears(item.getExperienceYears());
            return skill;
        }).toList();
        if (!skills.isEmpty()) {
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

        List<EngineerSkill> existing = baseMapper.selectListByEngineerIdAndTenant(engineerId, tenantId);
        Map<Long, Long> supersedesBySkillId = new HashMap<>();
        for (EngineerSkill skill : existing) {
            Long closedEventId = closeOpenSkillEvent(tenantId, engineerId, skill.getSkillId(), effectiveDate);
            if (closedEventId != null) {
                supersedesBySkillId.put(skill.getSkillId(), closedEventId);
            }
        }

        baseMapper.deleteByEngineerIdAndTenant(engineerId, tenantId);

        List<EngineerSkill> distinctSkills = skills.stream()
                .filter(distinctByKey(EngineerSkill::getSkillId))
                .peek(skill -> skill.setEngineerId(engineerId))
                .collect(Collectors.toList());

        for (EngineerSkill skill : distinctSkills) {
            if (baseMapper.insertForTenant(skill, tenantId) != 1) {
                throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
            }
        }

        for (EngineerSkill skill : distinctSkills) {
            assertNoOpenSkillEvent(tenantId, engineerId, skill.getSkillId());
            Long supersedesId = resolveSupersedesEventId(tenantId, engineerId, skill.getSkillId(), supersedesBySkillId);
            appendSkillEvent(skill, EngineerSkillEvent.TYPE_OPEN, effectiveDate, null,
                    supersedesId, actorUserId, actorRole, occurredAt, request.getReason().trim());
        }
        if (engineerMapper.bumpVersionForTenant(engineerId, tenantId, request.getExpectedVersion()) != 1) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    private Long resolveSupersedesEventId(String tenantId, Long engineerId, Long skillId, Map<Long, Long> closedInTx) {
        Long supersedesId = closedInTx.get(skillId);
        if (supersedesId != null) {
            return supersedesId;
        }
        EngineerSkillEvent lastClosed = engineerSkillEventMapper.selectLastClosedOpenEventForTenant(tenantId, engineerId, skillId);
        return lastClosed != null ? lastClosed.getId() : null;
    }

    private Long closeOpenSkillEvent(String tenantId, Long engineerId, Long skillId, LocalDate changeDate) {
        EngineerSkillEvent open = engineerSkillEventMapper.selectOpenEventForTenant(tenantId, engineerId, skillId);
        if (open == null) {
            return null;
        }
        LocalDate closeTo = EffectiveIntervalSupport.closeEffectiveTo(open.getEffectiveFrom(), changeDate);
        int rows = engineerSkillEventMapper.closeOpenEventForTenant(open.getId(), tenantId, closeTo);
        if (rows != 1) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }
        return open.getId();
    }

    private void assertNoOpenSkillEvent(String tenantId, Long engineerId, Long skillId) {
        if (engineerSkillEventMapper.selectOpenEventForTenant(tenantId, engineerId, skillId) != null) {
            throw com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    private void appendSkillEvent(EngineerSkill skill, String eventType, LocalDate effectiveFrom,
                                  LocalDate effectiveTo, Long supersedesEventId, Long actorUserId, String actorRole,
                                  LocalDateTime occurredAt, String reason) {
        EngineerSkillEvent event = new EngineerSkillEvent();
        event.setTenantId(requireTenant());
        event.setEngineerId(skill.getEngineerId());
        event.setEngineerSkillId(skill.getId());
        event.setSkillId(skill.getSkillId());
        event.setProficiency(skill.getProficiency());
        event.setExperienceYears(skill.getExperienceYears());
        event.setEventType(eventType);
        event.setEffectiveFrom(effectiveFrom);
        event.setEffectiveTo(effectiveTo);
        event.setSupersedesEventId(supersedesEventId);
        event.setActorUserId(actorUserId);
        event.setActorRoleSnapshot(actorRole);
        event.setReason(reason);
        event.setOccurredAt(occurredAt);
        event.setCreatedAt(occurredAt);
        engineerSkillEventMapper.insertEvent(event);
    }

    private SkillReplaceRequest.SkillItem toItem(EngineerSkill skill) {
        SkillReplaceRequest.SkillItem item = new SkillReplaceRequest.SkillItem();
        item.setSkillId(skill.getSkillId());
        item.setProficiency(skill.getProficiency());
        item.setExperienceYears(skill.getExperienceYears());
        return item;
    }

    private String requireTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }
}
