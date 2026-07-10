package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Project;
import org.apache.ibatis.annotations.Mapper;

/**
 * 案件マッパー
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
