package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.MfaRecoveryCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MfaRecoveryCodeMapper extends BaseMapper<MfaRecoveryCode> {

    @Select("SELECT * FROM t_mfa_recovery_code WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND code_hash = #{codeHash} AND used_at IS NULL AND revoked_at IS NULL "
            + "AND deleted_flag = 0 LIMIT 1")
    MfaRecoveryCode selectAvailable(@Param("tenantId") String tenantId,
                                    @Param("userId") Long userId,
                                    @Param("codeHash") String codeHash);

    @Update("UPDATE t_mfa_recovery_code SET used_at = #{usedAt}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND used_at IS NULL AND revoked_at IS NULL AND deleted_flag = 0")
    int markUsed(@Param("id") Long id, @Param("usedAt") LocalDateTime usedAt);

    @Update("UPDATE t_mfa_recovery_code SET revoked_at = #{revokedAt}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND used_at IS NULL "
            + "AND revoked_at IS NULL AND deleted_flag = 0")
    int revokeAvailable(@Param("tenantId") String tenantId,
                        @Param("userId") Long userId,
                        @Param("revokedAt") LocalDateTime revokedAt);
}
