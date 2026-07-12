package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.EngineerSkill;

import java.util.List;

public interface EngineerSkillService extends IService<EngineerSkill> {
    List<EngineerSkillDetailDto> listDetail(Long engineerId);
    void replaceSkills(Long engineerId, List<EngineerSkill> skills);
}
