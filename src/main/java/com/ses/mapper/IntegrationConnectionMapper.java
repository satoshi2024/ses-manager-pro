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

    /** Step 3 完了用: token確定・リース解放 (Fencing CAS — token_version と refresh_lease_token を同時照合) */
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

    /**
     * Step 1: リース獲得 CAS。
     * <ul>
     *   <li>条件: token_version = observedTokenVersion かつリース失効済み or 未保有</li>
     *   <li>成功 (1件更新): リース獲得。呼び出し元は即コミット後に HTTP へ進む。</li>
     *   <li>失敗 (0件更新): 他ノードがリース保有中。再読込して待機。</li>
     * </ul>
     */
    @Update("UPDATE m_integration_connection " +
            "SET refresh_lease_token = #{leaseToken}, " +
            "    refresh_lease_expires_at = #{leaseExpiresAt}, " +
            "    version = version + 1, updated_at = #{now} " +
            "WHERE id = #{id} " +
            "  AND token_version = #{observedTokenVersion} " +
            "  AND (refresh_lease_expires_at IS NULL OR refresh_lease_expires_at <= #{now}) " +
            "  AND deleted_flag = 0")
    int claimRefreshLeaseCas(@Param("id") Long id,
                             @Param("observedTokenVersion") int observedTokenVersion,
                             @Param("leaseToken") String leaseToken,
                             @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                             @Param("now") LocalDateTime now);

    /**
     * Step 3: 新トークン確定 Fencing CAS。
     * <ul>
     *   <li>条件: token_version = observedTokenVersion かつ refresh_lease_token = workerUuid</li>
     *   <li>成功: トークン確定、token_version インクリメント、リース解放。</li>
     *   <li>失敗: タイムアウト等でリースが他ノードに奪われた場合。新トークンを破棄して再読込。</li>
     * </ul>
     */
    @Update("UPDATE m_integration_connection " +
            "SET encrypted_tokens = #{encryptedTokens}, " +
            "    expires_at = #{expiresAt}, " +
            "    last_refreshed_at = #{now}, " +
            "    token_version = token_version + 1, " +
            "    status = 'CONNECTED', " +
            "    refresh_lease_token = NULL, " +
            "    refresh_lease_expires_at = NULL, " +
            "    version = version + 1, updated_at = #{now} " +
            "WHERE id = #{id} " +
            "  AND token_version = #{observedTokenVersion} " +
            "  AND refresh_lease_token = #{leaseToken} " +
            "  AND deleted_flag = 0")
    int commitRefreshTokenCas(@Param("id") Long id,
                              @Param("observedTokenVersion") int observedTokenVersion,
                              @Param("leaseToken") String leaseToken,
                              @Param("encryptedTokens") String encryptedTokens,
                              @Param("expiresAt") LocalDateTime expiresAt,
                              @Param("now") LocalDateTime now);

    /**
     * 再読込 (non-locking): Step 1 失敗後に最新 token_version とリース状態を確認する。
     */
    @Select("SELECT * FROM m_integration_connection WHERE id = #{id} AND deleted_flag = 0")
    IntegrationConnection selectCurrentState(@Param("id") Long id);

    /**
     * REAUTH_REQUIRED 設定: invalid_grant 等でリフレッシュトークンが失効した場合。
     * リース情報もクリアする。
     */
    @Update("UPDATE m_integration_connection " +
            "SET status = 'REAUTH_REQUIRED', " +
            "    refresh_lease_token = NULL, " +
            "    refresh_lease_expires_at = NULL, " +
            "    version = version + 1, updated_at = #{now} " +
            "WHERE id = #{id} AND deleted_flag = 0")
    int markReauthRequired(@Param("id") Long id,
                           @Param("now") LocalDateTime now);
}
