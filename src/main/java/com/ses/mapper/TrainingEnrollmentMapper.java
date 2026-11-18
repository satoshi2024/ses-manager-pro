package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.TrainingEnrollment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TrainingEnrollmentMapper extends BaseMapper<TrainingEnrollment> {

    @Select("SELECT * FROM t_training_enrollment WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    TrainingEnrollment selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_training_enrollment WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0 FOR UPDATE")
    TrainingEnrollment selectByIdForUpdateWithTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT status FROM t_training_enrollment WHERE plan_id = #{planId} AND tenant_id = #{tenantId} AND deleted_flag = 0")
    List<String> selectStatusesByPlanIdAndTenant(@Param("planId") Long planId, @Param("tenantId") String tenantId);
}
