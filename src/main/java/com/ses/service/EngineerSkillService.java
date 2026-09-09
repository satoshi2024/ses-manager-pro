package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.EngineerSkill;
import com.ses.dto.skill.SkillReplaceRequest;

import java.util.List;

public interface EngineerSkillService extends IService<EngineerSkill> {
    List<EngineerSkillDetailDto> listDetail(Long engineerId);
    List<EngineerSkill> listForTenant(Long engineerId);
    void replaceSkills(Long engineerId, List<EngineerSkill> skills);
    void replaceSkills(Long engineerId, SkillReplaceRequest request);
}
