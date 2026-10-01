package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Certification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CertificationMapper extends BaseMapper<Certification> {

    @Select("SELECT * FROM m_certification WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND deleted_flag = 0")
    Certification selectByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);
}
