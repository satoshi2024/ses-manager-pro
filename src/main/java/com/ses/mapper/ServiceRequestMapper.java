package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * サービスリクエストマッパー
 */
@Mapper
public interface ServiceRequestMapper extends BaseMapper<ServiceRequest> {

    @Select("SELECT * FROM t_service_request WHERE id = #{id} AND tenant_id = #{tenantId}")
    ServiceRequest selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId);
}
