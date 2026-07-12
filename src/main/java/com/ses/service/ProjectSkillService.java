package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.project.ProjectSkillDetailDto;
import com.ses.entity.ProjectSkill;

import java.util.List;

public interface ProjectSkillService extends IService<ProjectSkill> {
    List<ProjectSkillDetailDto> listDetail(Long projectId);
    void replaceSkills(Long projectId, List<ProjectSkill> skills);
}
