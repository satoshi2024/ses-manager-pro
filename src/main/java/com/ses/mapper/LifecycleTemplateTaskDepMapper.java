package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleTemplateTaskDep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LifecycleTemplateTaskDepMapper extends BaseMapper<LifecycleTemplateTaskDep> {

    @Select("SELECT * FROM m_lifecycle_template_task_dep WHERE template_id = #{templateId}")
    List<LifecycleTemplateTaskDep> selectByTemplateId(@Param("templateId") Long templateId);
}
