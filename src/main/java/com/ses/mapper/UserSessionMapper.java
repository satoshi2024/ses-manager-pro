package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.UserSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

    @Select("SELECT * FROM t_user_session WHERE tenant_id = #{tenantId} "
            + "AND session_id_hash = #{sessionIdHash} AND deleted_flag = 0 LIMIT 1")
    UserSession selectBySessionHash(@Param("tenantId") String tenantId,
                                    @Param("sessionIdHash") String sessionIdHash);

    @Select("SELECT * FROM t_user_session WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND revoked_at IS NULL AND idle_expires_at > #{now} AND expires_at > #{now} "
            + "AND deleted_flag = 0 ORDER BY issued_at DESC")
    List<UserSession> selectActiveByUser(@Param("tenantId") String tenantId,
                                         @Param("userId") Long userId,
                                         @Param("now") LocalDateTime now);

    /** 同時session上限のquery→revoke→insertを同一ユーザー単位で直列化する。 */
    @Select("SELECT * FROM t_user_session WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND revoked_at IS NULL AND idle_expires_at > #{now} AND expires_at > #{now} "
            + "AND deleted_flag = 0 ORDER BY issued_at DESC FOR UPDATE")
    List<UserSession> selectActiveByUserForUpdate(@Param("tenantId") String tenantId,
                                                   @Param("userId") Long userId,
                                                   @Param("now") LocalDateTime now);

    @Update("UPDATE t_user_session SET last_seen_at = #{now}, idle_expires_at = #{idleExpiresAt}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND revoked_at IS NULL "
            + "AND idle_expires_at > #{now} AND expires_at > #{now} AND deleted_flag = 0")
    int touch(@Param("id") Long id,
              @Param("now") LocalDateTime now,
              @Param("idleExpiresAt") LocalDateTime idleExpiresAt);

    @Update("UPDATE t_user_session SET revoked_at = #{now}, revoke_reason = #{reason}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND revoked_at IS NULL AND deleted_flag = 0")
    int revoke(@Param("id") Long id,
               @Param("now") LocalDateTime now,
               @Param("reason") String reason);

    @Update("UPDATE t_user_session SET revoked_at = #{now}, revoke_reason = #{reason}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND revoked_at IS NULL AND deleted_flag = 0")
    int revokeAllForUser(@Param("tenantId") String tenantId,
                         @Param("userId") Long userId,
                         @Param("now") LocalDateTime now,
                         @Param("reason") String reason);

    @Update("UPDATE t_user_session SET revoked_at = #{now}, revoke_reason = #{reason}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND session_id_hash <> #{currentHash} AND revoked_at IS NULL AND deleted_flag = 0")
    int revokeOthers(@Param("tenantId") String tenantId,
                     @Param("userId") Long userId,
                     @Param("currentHash") String currentHash,
                     @Param("now") LocalDateTime now,
                     @Param("reason") String reason);
}
