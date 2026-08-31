package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiClient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** NF-05 client binding mapper。 */
@Mapper
public interface ApiClientMapper extends BaseMapper<ApiClient> {
    @Select("SELECT * FROM m_api_client WHERE client_id = #{clientId} LIMIT 1")
    ApiClient selectByClientId(@Param("clientId") String clientId);

    @Select("SELECT * FROM m_api_client WHERE client_id = #{clientId} FOR UPDATE")
    ApiClient selectByClientIdForUpdate(@Param("clientId") String clientId);
}
