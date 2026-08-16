package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * ポータルセッションマッパー（t_portal_session）。
 */
@Mapper
public interface PortalSessionMapper extends BaseMapper<PortalSession> {

    @Select("SELECT * FROM t_portal_session WHERE token_hash = #{tokenHash}")
    PortalSession selectByTokenHash(String tokenHash);

    /**
     * 有効なsessionを検証しつつlast_seenを更新する。失効・期限切れなら0件。
     */
    @Update("UPDATE t_portal_session SET last_seen_at = #{now}, idle_expires_at = #{idleExpiresAt}"
            + " WHERE id = #{id} AND revoked_at IS NULL"
            + " AND idle_expires_at > #{now} AND expires_at > #{now}")
    int touchIfValid(@Param("id") Long id, @Param("now") LocalDateTime now,
                     @Param("idleExpiresAt") LocalDateTime idleExpiresAt);

    /**
     * userの全有効sessionを失効させる（user停止・MFA reset・password変更・管理者操作）。
     */
    @Update("UPDATE t_portal_session SET revoked_at = #{now}, revoked_reason = #{reason}"
            + " WHERE user_id = #{userId} AND revoked_at IS NULL")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now,
                         @Param("reason") String reason);

    /**
     * 組織配下の全userの全有効sessionを失効させる（組織停止時）。
     * H2互換のためJOINではなくINサブクエリで書く（MySQL/H2両対応）。
     */
    @Update("UPDATE t_portal_session SET revoked_at = #{now}, revoked_reason = #{reason}"
            + " WHERE revoked_at IS NULL"
            + " AND user_id IN (SELECT id FROM t_portal_user WHERE portal_org_id = #{portalOrgId})")
    int revokeAllForOrg(@Param("portalOrgId") Long portalOrgId, @Param("now") LocalDateTime now,
                        @Param("reason") String reason);
}
