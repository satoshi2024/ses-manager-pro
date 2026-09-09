package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.CostCenter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 原価部門マスタMapper。 */
@Mapper
public interface CostCenterMapper extends BaseMapper<CostCenter> {
    /** 組織tenantを経由して承認の原価部門を解決する。 */
    @Select("SELECT cc.* FROM m_cost_center cc "
            + "JOIN m_organization_unit ou ON ou.id = cc.organization_id "
            + "AND CAST(ou.tenant_id AS CHAR) = #{tenantId} AND ou.deleted_flag = 0 "
            + "WHERE cc.id = #{id} AND cc.deleted_flag = 0")
    CostCenter selectByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);
}
