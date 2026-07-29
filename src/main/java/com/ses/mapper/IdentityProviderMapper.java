package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.IdentityProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IdentityProviderMapper extends BaseMapper<IdentityProvider> {

    @Select("SELECT * FROM m_identity_provider WHERE tenant_id = #{tenantId} "
            + "AND issuer_uri = #{issuerUri} AND enabled = 1 AND deleted_flag = 0 LIMIT 1")
    IdentityProvider selectEnabledByTenantAndIssuer(@Param("tenantId") String tenantId,
                                                     @Param("issuerUri") String issuerUri);
}
