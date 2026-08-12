package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Workplace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkplaceMapper extends BaseMapper<Workplace> {

    /**
     * §2.2・G2-ASG: assignment作成の直列化用anchor lock。
     * 同一workplaceの並行createAssignmentをFOR UPDATEで直列化する（既存行がない場合の挿入はUNIQUE制約に委ねる）。
     */
    @Select("SELECT id FROM m_workplace WHERE tenant_id = #{tenantId} AND id = #{workplaceId} FOR UPDATE")
    Long selectIdForUpdate(@Param("tenantId") String tenantId, @Param("workplaceId") Long workplaceId);
}
