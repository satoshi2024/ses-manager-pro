package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.Project;
import com.ses.mapper.ProjectMapper;
import com.ses.service.ProjectService;
import org.springframework.stereotype.Service;

/**
 * 案件サービス実装クラス
 */
@Service
public class ProjectServiceImpl extends ServiceImpl<ProjectMapper, Project> implements ProjectService {
}
