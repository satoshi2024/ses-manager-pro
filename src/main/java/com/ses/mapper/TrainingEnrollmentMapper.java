package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.TrainingEnrollment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TrainingEnrollmentMapper extends BaseMapper<TrainingEnrollment> {

    @Select("SELECT * FROM t_training_enrollment WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    TrainingEnrollment selectByIdForUpdate(@Param("id") Long id);
}
