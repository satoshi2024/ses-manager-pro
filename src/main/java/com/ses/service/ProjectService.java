package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.Project;

import com.ses.dto.project.ProjectSaveDto;

/**
 * 案件サービスインターフェース
 */
public interface ProjectService extends IService<Project> {
    void saveProjectWithSkills(ProjectSaveDto dto);
    boolean updateProjectWithSkills(ProjectSaveDto dto);
}
