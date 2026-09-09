package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.BpCompany;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BpCompanyMapper extends BaseMapper<BpCompany> {

    @Select("SELECT * FROM m_bp_company WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0")
    BpCompany selectByIdForTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
