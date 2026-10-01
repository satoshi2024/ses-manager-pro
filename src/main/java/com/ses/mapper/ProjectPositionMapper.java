package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ProjectPosition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 案件ポジション（募集枠） */
@Mapper
public interface ProjectPositionMapper extends BaseMapper<ProjectPosition> {

    /** 契約から参照するポジションは案件・顧客のtenant ownershipを同時に検証する。 */
    @Select("SELECT pp.* FROM t_project_position pp "
            + "JOIN t_project p ON p.id = pp.project_id AND p.deleted_flag = 0 "
            + "JOIN m_customer c ON c.id = p.customer_id AND c.deleted_flag = 0 "
            + "AND c.tenant_id = #{tenantId} "
            + "WHERE pp.id = #{id} AND pp.deleted_flag = 0")
    ProjectPosition selectByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);
}
