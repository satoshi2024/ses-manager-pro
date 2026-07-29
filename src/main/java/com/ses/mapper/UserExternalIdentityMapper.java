package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.UserExternalIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserExternalIdentityMapper extends BaseMapper<UserExternalIdentity> {

    @Select("SELECT * FROM t_user_external_identity WHERE tenant_id = #{tenantId} "
            + "AND provider_id = #{providerId} AND subject = #{subject} "
            + "AND deleted_flag = 0 LIMIT 1")
    UserExternalIdentity selectByTenantProviderAndSubject(@Param("tenantId") String tenantId,
                                                           @Param("providerId") Long providerId,
                                                           @Param("subject") String subject);
}
