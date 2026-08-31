package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.CredentialVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** NF-05 credential version mapper。 */
@Mapper
public interface CredentialVersionMapper extends BaseMapper<CredentialVersion> {
    @Select("SELECT * FROM t_credential_version WHERE api_client_id = #{clientId} "
            + "AND status IN ('ACTIVE', 'OVERLAP') ORDER BY credential_version DESC FOR UPDATE")
    List<CredentialVersion> selectRotatable(@Param("clientId") Long clientId);
    @Select("SELECT * FROM t_credential_version WHERE api_client_id = #{clientId} "
            + "AND credential_version = #{credentialVersion} LIMIT 1")
    CredentialVersion selectByClientAndVersion(@Param("clientId") Long clientId,
                                               @Param("credentialVersion") Integer credentialVersion);

    @Select("SELECT * FROM t_credential_version WHERE api_client_id = #{clientId} AND key_id = #{keyId} "
            + "AND status IN ('ACTIVE', 'OVERLAP') AND issued_at <= #{now} "
            + "AND expires_at > #{now} AND (revoked_at IS NULL OR revoked_at > #{now}) "
            + "AND (status <> 'OVERLAP' OR overlap_until IS NOT NULL AND overlap_until > #{now}) LIMIT 1")
    CredentialVersion selectUsable(@Param("clientId") Long clientId,
                                   @Param("keyId") String keyId,
                                   @Param("now") LocalDateTime now);

    @Select("SELECT * FROM t_credential_version WHERE api_client_id = #{clientId} "
            + "AND credential_version = #{credentialVersion} FOR UPDATE")
    CredentialVersion selectForUpdate(@Param("clientId") Long clientId,
                                      @Param("credentialVersion") Integer credentialVersion);

    @org.apache.ibatis.annotations.Update("UPDATE t_credential_version SET status = 'REVOKED', revoked_at = #{revokedAt}, "
            + "overlap_until = NULL, version = version + 1, updated_at = #{revokedAt} "
            + "WHERE api_client_id = #{clientId} AND credential_version = #{credentialVersion} "
            + "AND version = #{version} AND status IN ('ACTIVE', 'OVERLAP')")
    int revoke(@Param("clientId") Long clientId, @Param("credentialVersion") Integer credentialVersion,
               @Param("version") Integer version, @Param("revokedAt") LocalDateTime revokedAt);
}
