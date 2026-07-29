package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.UserMfa;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMfaMapper extends BaseMapper<UserMfa> {

    @Select("SELECT COUNT(*) FROM t_user_mfa WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND encrypted_totp_secret IS NOT NULL AND enabled_at IS NOT NULL AND deleted_flag = 0")
    int countEnrolled(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    @Update("UPDATE t_user_mfa SET last_used_step = #{step}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted_flag = 0 "
            + "AND (last_used_step IS NULL OR last_used_step < #{step})")
    int advanceLastUsedStep(@Param("id") Long id, @Param("step") long step);

    @Update("UPDATE t_user_mfa SET encrypted_totp_secret = #{encryptedSecret}, "
            + "secret_key_version = #{keyVersion}, enabled_at = NULL, last_used_step = NULL, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted_flag = 0")
    int prepareSetup(@Param("id") Long id,
                     @Param("encryptedSecret") String encryptedSecret,
                     @Param("keyVersion") String keyVersion);

    @Update("UPDATE t_user_mfa SET enabled_at = #{enabledAt}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted_flag = 0")
    int enable(@Param("id") Long id, @Param("enabledAt") java.time.LocalDateTime enabledAt);

    @Update("UPDATE t_user_mfa SET encrypted_totp_secret = NULL, secret_key_version = NULL, "
            + "enabled_at = NULL, last_used_step = NULL, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted_flag = 0")
    int clear(@Param("id") Long id);
}
