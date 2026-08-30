package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LicensePlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LicensePlanMapper extends BaseMapper<LicensePlan> {

    @Select("SELECT * FROM m_license_plan WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    LicensePlan selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE m_license_plan SET allocated_count = allocated_count + 1, version = version + 1 " +
            "WHERE id = #{id} AND allocated_count < seat_limit AND version = #{expectedVersion} AND deleted_flag = 0")
    int incrementAllocatedCountWithCas(@Param("id") Long id,
                                       @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE m_license_plan SET allocated_count = GREATEST(0, allocated_count - 1), version = version + 1 " +
            "WHERE id = #{id} AND version = #{expectedVersion} AND deleted_flag = 0")
    int decrementAllocatedCountWithCas(@Param("id") Long id,
                                       @Param("expectedVersion") Integer expectedVersion);
}
