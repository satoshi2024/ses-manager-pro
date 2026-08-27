package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleTemplateTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LifecycleTemplateTaskMapper extends BaseMapper<LifecycleTemplateTask> {

    @Select("SELECT * FROM m_lifecycle_template_task " +
            "WHERE template_id = #{templateId} AND deleted_flag = 0 " +
            "ORDER BY sort_order ASC, id ASC")
    List<LifecycleTemplateTask> selectByTemplateId(@Param("templateId") Long templateId);
}
