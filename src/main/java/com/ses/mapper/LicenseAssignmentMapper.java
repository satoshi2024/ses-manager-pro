package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LicenseAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LicenseAssignmentMapper extends BaseMapper<LicenseAssignment> {

    @Select("SELECT * FROM t_license_assignment " +
            "WHERE assignee_type = #{assigneeType} AND assignee_id = #{assigneeId} " +
            "  AND status = 'ACTIVE' AND deleted_flag = 0")
    List<LicenseAssignment> selectActiveByAssignee(@Param("assigneeType") String assigneeType,
                                                   @Param("assigneeId") Long assigneeId);
}
