package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.OrganizationUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 組織マスタMapper。 */
@Mapper
public interface OrganizationUnitMapper extends BaseMapper<OrganizationUnit> {

    @Select("SELECT * FROM m_organization_unit WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    OrganizationUnit selectByIdForUpdate(Long id);
}
