package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiClientScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** NF-05 client scope / operation permission mapper。 */
@Mapper
public interface ApiClientScopeMapper extends BaseMapper<ApiClientScope> {
    @Select("SELECT * FROM m_api_client_scope WHERE api_client_id = #{clientId} "
            + "AND status = 'ACTIVE' ORDER BY scope_code, operation_code")
    List<ApiClientScope> selectActiveByClientId(@Param("clientId") Long clientId);

    @Select("SELECT * FROM m_api_client_scope WHERE api_client_id = #{clientId} "
            + "AND scope_code = #{scopeCode} AND operation_code = #{operationCode} "
            + "AND status = 'ACTIVE' LIMIT 1")
    ApiClientScope selectActive(@Param("clientId") Long clientId,
                                @Param("scopeCode") String scopeCode,
                                @Param("operationCode") String operationCode);

    @Select("SELECT * FROM m_api_client_scope WHERE api_client_id = #{clientId} "
            + "AND scope_code = #{scopeCode} AND operation_code = #{operationCode} "
            + "AND status = 'ACTIVE' LIMIT 1 FOR UPDATE")
    ApiClientScope selectActiveForUpdate(@Param("clientId") Long clientId,
                                         @Param("scopeCode") String scopeCode,
                                         @Param("operationCode") String operationCode);
}
