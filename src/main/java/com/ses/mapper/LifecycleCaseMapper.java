package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LifecycleCaseMapper extends BaseMapper<LifecycleCase> {

    @Select("SELECT * FROM t_lifecycle_case WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    LifecycleCase selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT l.* FROM t_lifecycle_case l "
            + "INNER JOIN t_engineer_certification c ON c.engineer_id = l.engineer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "WHERE l.engineer_id = #{engineerId} AND l.tenant_id = #{tenantId} "
            + "AND l.deleted_flag = 0 ORDER BY l.anchor_date, l.id")
    java.util.List<LifecycleCase> selectByEngineerIdAndTenant(@Param("engineerId") Long engineerId,
                                                               @Param("tenantId") String tenantId);

    @Select("SELECT MAX(case_no) FROM t_lifecycle_case WHERE case_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxCaseNoIncludingDeleted(@Param("prefix") String prefix);
}
