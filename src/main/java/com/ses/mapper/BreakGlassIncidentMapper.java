package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.BreakGlassIncident;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface BreakGlassIncidentMapper extends BaseMapper<BreakGlassIncident> {

    @Select("SELECT * FROM t_break_glass_incident WHERE tenant_id = #{tenantId} "
            + "AND status = 'ACTIVE' AND idp_outage_confirmed = 1 "
            + "AND approved_by_1 IS NOT NULL AND approved_by_2 IS NOT NULL "
            + "AND approved_by_1 <> approved_by_2 AND enabled_from <= #{now} AND enabled_until > #{now} "
            + "AND deleted_flag = 0 ORDER BY enabled_from DESC LIMIT 1")
    BreakGlassIncident selectActive(@Param("tenantId") String tenantId, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM t_break_glass_incident WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND deleted_flag = 0 FOR UPDATE")
    BreakGlassIncident selectByIdForUpdate(@Param("tenantId") String tenantId, @Param("id") Long id);
}
