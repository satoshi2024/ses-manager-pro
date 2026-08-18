package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.IntegrationConnection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface IntegrationConnectionMapper extends BaseMapper<IntegrationConnection> {

    @Select("SELECT * FROM m_integration_connection WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    IntegrationConnection selectForUpdate(@Param("id") Long id);

    @Update("UPDATE m_integration_connection " +
            "SET encrypted_tokens = #{encryptedTokens}, expires_at = #{expiresAt}, " +
            "    status = #{status}, last_refreshed_at = #{lastRefreshedAt}, " +
            "    version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND deleted_flag = 0")
    int updateTokensCas(@Param("id") Long id,
                        @Param("version") int version,
                        @Param("encryptedTokens") String encryptedTokens,
                        @Param("expiresAt") LocalDateTime expiresAt,
                        @Param("status") String status,
                        @Param("lastRefreshedAt") LocalDateTime lastRefreshedAt);
}
