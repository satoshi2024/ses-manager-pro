package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.TrainingCourseSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TrainingCourseSkillMapper extends BaseMapper<TrainingCourseSkill> {

    @Delete("DELETE FROM t_training_course_skill WHERE tenant_id = #{tenantId} "
            + "AND course_id = #{courseId} AND deleted_flag = 0")
    int deleteByCourseForTenant(@Param("tenantId") String tenantId, @Param("courseId") Long courseId);
}
