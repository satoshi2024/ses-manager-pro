package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ProjectPosition;
import org.apache.ibatis.annotations.Mapper;

/** 案件ポジション（募集枠） */
@Mapper
public interface ProjectPositionMapper extends BaseMapper<ProjectPosition> {
}
