package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LearningPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LearningPlanMapper extends BaseMapper<LearningPlan> {

    @Select("SELECT * FROM t_learning_plan WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    LearningPlan selectByIdForUpdate(@Param("id") Long id);
}
