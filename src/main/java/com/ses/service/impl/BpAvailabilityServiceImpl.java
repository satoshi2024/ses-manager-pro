package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.BpAvailability;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerSkill;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.service.BpAvailabilityService;
import com.ses.service.EngineerService;
import com.ses.service.SkillTagResolver;
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
    private final EngineerSkillMapper engineerSkillMapper;
    private final SkillTagResolver skillTagResolver;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Engineer promoteToEngineer(Long id) {
        BpAvailability availability = this.getById(id);
        if (availability == null) {
            throw BusinessException.of(404, "error.bpAvailability.notFound");
        }
        if (availability.getPromotedEngineerId() != null) {
            throw BusinessException.of(409, "error.bpAvailability.alreadyPromoted");
        }

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
        
        com.ses.common.util.EntityProtectUtil.protectForCreate(engineer);
        engineerService.save(engineer);

        if (availability.getSkillsJson() != null && !availability.getSkillsJson().isBlank()) {
            try {
                List<String> skills = objectMapper.readValue(availability.getSkillsJson(), new TypeReference<List<String>>() {});
                for (String skill : skills) {
                    Long skillId = skillTagResolver.resolveOrCreate(skill);
                    EngineerSkill engSkill = new EngineerSkill();
                    engSkill.setEngineerId(engineer.getId());
                    engSkill.setSkillId(skillId);
                    engSkill.setProficiency("中級"); // Default value
                    engineerSkillMapper.insert(engSkill);
                }
            } catch (Exception e) {
                // Ignore parse error
            }
        }
        
        availability.setPromotedEngineerId(engineer.getId());
        availability.setStatus("要員化済");
        this.updateById(availability);

        return engineer;
    }

    /**
     * 毎日午前3時に実行。
     * 最終更新日から60日以上経過した「提案可能」な要員のステータスを「失効」に更新する。
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 3 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void expireBpAvailabilities() {
        java.time.LocalDateTime threshold = java.time.LocalDateTime.now().minusDays(60);
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BpAvailability> wrapper = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        wrapper.eq(BpAvailability::getStatus, "提案可能")
               .lt(BpAvailability::getUpdatedAt, threshold)
               .set(BpAvailability::getStatus, "失効");
        this.update(wrapper);
    }
}
