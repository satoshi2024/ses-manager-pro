package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.entity.BpAvailability;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerSkill;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.service.BpAvailabilityService;
import com.ses.service.EngineerSkillService;
import com.ses.service.EngineerService;
import com.ses.service.SkillTagResolver;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BpAvailabilityServiceImpl extends ServiceImpl<BpAvailabilityMapper, BpAvailability> implements BpAvailabilityService {

    private final EngineerService engineerService;
    private final EngineerSkillService engineerSkillService;
    private final SkillTagResolver skillTagResolver;
    private final ObjectMapper objectMapper;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.LegalEntityContextService legalEntityContextService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(BpAvailability entity) {
        String tenantId = requireTenant();
        if (entity == null) {
            return false;
        }
        if (entity.getTenantId() != null && !tenantId.equals(entity.getTenantId())) {
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
        entity.setTenantId(tenantId);
        if (legalEntityContextService != null) {
            entity.setLegalEntityId(legalEntityContextService.requireCurrentLegalEntityId());
        }
        return super.save(entity);
    }

    @Override
    public Page<BpAvailability> pageForCurrentTenant(Page<BpAvailability> page, String status) {
        return baseMapper.selectPageForTenant(page, requireTenant(), status);
    }

    @Override
    public BpAvailability getForCurrentTenant(Long id) {
        return baseMapper.selectByIdForTenant(id, requireTenant());
    }

    @Override
    public boolean updateForCurrentTenant(Long id, BpAvailability availability) {
        String tenantId = requireTenant();
        if (availability == null) {
            return false;
        }
        if (availability.getTenantId() != null && !tenantId.equals(availability.getTenantId())) {
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
        BpAvailability existing = baseMapper.selectByIdForTenant(id, tenantId);
        if (existing == null) {
            return false;
        }
        if (availability.getInitialName() != null) existing.setInitialName(availability.getInitialName());
        if (availability.getSkillsJson() != null) existing.setSkillsJson(availability.getSkillsJson());
        if (availability.getUnitPrice() != null) existing.setUnitPrice(availability.getUnitPrice());
        if (availability.getAvailableFrom() != null) existing.setAvailableFrom(availability.getAvailableFrom());
        if (availability.getExperienceYears() != null) existing.setExperienceYears(availability.getExperienceYears());
        if (availability.getStatus() != null) existing.setStatus(availability.getStatus());
        if (availability.getPromotedEngineerId() != null) existing.setPromotedEngineerId(availability.getPromotedEngineerId());
        if (availability.getRemarks() != null) existing.setRemarks(availability.getRemarks());
        existing.setTenantId(tenantId);
        return baseMapper.updateByIdForTenant(id, tenantId, existing) > 0;
    }

    @Override
    public boolean removeForCurrentTenant(Long id) {
        return baseMapper.removeByIdForTenant(id, requireTenant()) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(BpAvailability entity) {
        if (entity == null || entity.getId() == null || legalEntityContextService == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        BpAvailability existing = super.getById(entity.getId());
        if (existing == null || existing.getLegalEntityId() == null) {
            throw BusinessException.of(404, "error.bpAvailability.notFound");
        }
        legalEntityContextService.assertCurrent(existing.getLegalEntityId());
        if (entity.getLegalEntityId() != null) {
            legalEntityContextService.assertSame(existing.getLegalEntityId(), entity.getLegalEntityId());
        }
        entity.setLegalEntityId(existing.getLegalEntityId());
        return super.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Engineer promoteToEngineer(Long id) {
        String tenantId = requireTenant();
        BpAvailability availability = baseMapper.selectByIdForTenant(id, tenantId);
        if (availability == null) {
            throw BusinessException.of(404, "error.bpAvailability.notFound");
        }
        if (availability.getPromotedEngineerId() != null) {
            throw BusinessException.of(409, "error.bpAvailability.alreadyPromoted");
        }
        if (legalEntityContextService == null || availability.getLegalEntityId() == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        legalEntityContextService.assertCurrent(availability.getLegalEntityId());

        Engineer engineer = new Engineer();
        engineer.setFullName(availability.getInitialName() != null ? availability.getInitialName() : "未設定");
        engineer.setInitialName(availability.getInitialName());
        engineer.setEmploymentType("BP");
        engineer.setRemarks(availability.getBpCompany() != null ? availability.getBpCompany() + "\n" + (availability.getRemarks() != null ? availability.getRemarks() : "") : availability.getRemarks());
        engineer.setExperienceYears(availability.getExperienceYears());
        engineer.setAvailableDate(availability.getAvailableFrom());
        if (availability.getUnitPrice() != null) {
            engineer.setExpectedUnitPrice(new BigDecimal(availability.getUnitPrice()));
        }
        engineer.setStatus("Bench");
        engineer.setLegalEntityId(availability.getLegalEntityId());
        
        com.ses.common.util.EntityProtectUtil.protectForCreate(engineer);
        engineerService.save(engineer);

        if (availability.getSkillsJson() != null && !availability.getSkillsJson().isBlank()) {
            try {
                List<String> skills = objectMapper.readValue(availability.getSkillsJson(), new TypeReference<List<String>>() {});
                List<EngineerSkill> engineerSkills = new java.util.ArrayList<>();
                for (String skill : skills) {
                    Long skillId = skillTagResolver.resolveOrCreate(skill);
                    EngineerSkill engSkill = new EngineerSkill();
                    engSkill.setSkillId(skillId);
                    engSkill.setProficiency("中級"); // Default value
                    engineerSkills.add(engSkill);
                }
                com.ses.dto.skill.SkillReplaceRequest request = new com.ses.dto.skill.SkillReplaceRequest();
                request.setExpectedVersion(0);
                request.setReason("BP稼働情報から要員化");
                request.setSkills(engineerSkills.stream().map(skill -> {
                    com.ses.dto.skill.SkillReplaceRequest.SkillItem item =
                            new com.ses.dto.skill.SkillReplaceRequest.SkillItem();
                    item.setSkillId(skill.getSkillId());
                    item.setProficiency(skill.getProficiency());
                    item.setExperienceYears(skill.getExperienceYears());
                    return item;
                }).toList());
                engineerSkillService.replaceSkills(engineer.getId(), request);
            } catch (Exception e) {
                if (e instanceof BusinessException businessException) {
                    throw businessException;
                }
                throw BusinessException.of(400, "error.bpAvailability.skillsInvalid");
            }
        }
        
        availability.setPromotedEngineerId(engineer.getId());
        availability.setStatus("要員化済");
        if (baseMapper.updateByIdForTenant(id, tenantId, availability) == 0) {
            throw BusinessException.of(404, "error.bpAvailability.notFound");
        }

        return engineer;
    }

    /**
     * 毎日午前3時に実行。
     * 最終更新日から60日以上経過した「提案可能」な要員のステータスを「失効」に更新する。
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 3 * * ?")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "bpAvailabilityExpireDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    @Transactional(rollbackFor = Exception.class)
    public void expireBpAvailabilities() {
        tenantAwareBatchRunner.run(tenantId -> {
            java.time.LocalDateTime threshold = java.time.LocalDateTime.now(
                    AccountingTenantContextHolder.getZoneId()).minusDays(60);
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BpAvailability> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            wrapper.eq(BpAvailability::getTenantId, tenantId)
                   .eq(BpAvailability::getStatus, "提案可能")
                   .isNotNull(BpAvailability::getLegalEntityId)
                   .lt(BpAvailability::getUpdatedAt, threshold)
                   .set(BpAvailability::getStatus, "失効");
            this.update(wrapper);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void review(Long id, boolean approved, String comment) {
        BpAvailability availability = this.getById(id);
        if (availability == null) {
            throw BusinessException.of(404, "error.bpAvailability.notFound");
        }
        if (legalEntityContextService == null || availability.getLegalEntityId() == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        legalEntityContextService.assertCurrent(availability.getLegalEntityId());
        // 状態CAS（design §6.3）: 未確認→提案可能/却下。二重reviewの敗者は0件で409。
        String tenantId = requireTenant();
        String next = approved
                ? com.ses.service.portal.impl.PortalBpServiceImpl.AVAILABILITY_ACTIVE
                : com.ses.service.portal.impl.PortalBpServiceImpl.AVAILABILITY_REJECTED;
        boolean updated = this.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BpAvailability>()
                .eq(BpAvailability::getId, id)
                .eq(BpAvailability::getTenantId, tenantId)
                .eq(BpAvailability::getStatus, com.ses.service.portal.impl.PortalBpServiceImpl.AVAILABILITY_PENDING)
                .set(BpAvailability::getStatus, next)
                .set(comment != null && !comment.isBlank(), BpAvailability::getRemarks, comment == null ? null : comment.trim()));
        if (!updated) {
            BpAvailability existing = baseMapper.selectByIdForTenant(id, tenantId);
            if (existing == null) {
                throw BusinessException.of(404, "error.bpAvailability.notFound");
            }
            throw BusinessException.of(409, "error.portal.bp.availabilityReviewed");
        }
    }

    private String requireTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }
}
