package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ExternalMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExternalMappingMapper extends BaseMapper<ExternalMapping> {

    /** mappingIdとconnection.tenant_idを同一SQLで確認する (R4-R3 / design §5.2)。 */
    @Select("""
            SELECT m.*
            FROM m_external_mapping m
            INNER JOIN m_integration_connection c
                ON c.id = m.connection_id
               AND c.deleted_flag = 0
               AND c.tenant_id = #{tenantId}
            WHERE m.id = #{mappingId}
              AND m.deleted_flag = 0
            """)
    ExternalMapping selectByIdScopedToTenant(@Param("mappingId") Long mappingId,
                                              @Param("tenantId") String tenantId);
}
