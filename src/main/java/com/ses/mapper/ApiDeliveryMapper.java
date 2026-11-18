package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiDelivery;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** NF-05専用delivery ledger mapper。claim/HTTP/CASの境界を保持する。 */
@Mapper
public interface ApiDeliveryMapper extends BaseMapper<ApiDelivery> {
    @Select("SELECT COALESCE(MAX(id), 0) FROM t_api_delivery")
    long selectHighWater();

    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM t_api_delivery WHERE id <= #{highWater} AND (status IN ('PENDING','RETRYABLE') "
            + "OR (status = 'CLAIMED' AND lease_expires_at <= #{now})) ORDER BY id LIMIT #{limit}")
    List<ApiDelivery> selectHistoricalForQuarantine(@Param("highWater") long highWater, @Param("now") LocalDateTime now,
                                                    @Param("limit") int limit);

    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("SELECT EXISTS(SELECT 1 FROM t_api_delivery WHERE id <= #{highWater} AND status IN ('PENDING','RETRYABLE','CLAIMED'))")
    boolean hasHistoricalActive(@Param("highWater") long highWater);

    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("SELECT MIN(a.created_at) FROM t_api_delivery_replay_audit a JOIN t_api_delivery original ON original.id=a.delivery_id "
            + "WHERE a.event_id=#{eventId} AND a.replay_generation=#{generation} AND original.subscription_id=#{subscriptionId} "
            + "AND original.client_id=#{clientId}")
    LocalDateTime selectReplayWindowStart(@Param("eventId") String eventId, @Param("generation") int generation,
                                          @Param("subscriptionId") Long subscriptionId, @Param("clientId") String clientId);

    @Update("UPDATE t_api_delivery SET status='DLQ', last_error_code='STARTUP_REVALIDATION_REQUIRED', "
            + "retention_class='FAILED_DLQ_PAYLOAD_90D', retention_expires_at=#{expiry}, terminal_at=#{now}, "
            + "lease_token=NULL, lease_expires_at=NULL, version=version+1, updated_at=#{now} "
            + "WHERE id=#{id} AND version=#{version} AND (status IN ('PENDING','RETRYABLE') "
            + "OR (status='CLAIMED' AND lease_expires_at <= #{now}))")
    int quarantineHistorical(@Param("id") Long id, @Param("version") Integer version,
                             @Param("now") LocalDateTime now, @Param("expiry") LocalDateTime expiry);

    @Select("SELECT * FROM t_api_delivery WHERE event_id = #{eventId} "
            + "AND subscription_id = #{subscriptionId} AND delivery_generation = #{generation} LIMIT 1")
    ApiDelivery selectByEventGeneration(@Param("eventId") String eventId,
                                        @Param("subscriptionId") Long subscriptionId,
                                        @Param("generation") Integer generation);

    @Select("SELECT * FROM t_api_delivery WHERE id = #{id} FOR UPDATE")
    ApiDelivery selectForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_api_delivery WHERE status IN ('PENDING', 'RETRYABLE') "
            + "AND next_attempt_at <= #{now} ORDER BY id LIMIT #{limit}")
    List<ApiDelivery> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE t_api_delivery SET status = 'CLAIMED', lease_token = #{leaseToken}, "
            + "lease_expires_at = #{leaseExpiresAt}, attempt_count = attempt_count + 1, "
            + "version = version + 1, updated_at = #{now} "
            + "WHERE id = #{id} AND version = #{version} AND status IN ('PENDING', 'RETRYABLE') "
            + "AND next_attempt_at <= #{now} AND attempt_count < max_attempts")
    int claim(@Param("id") Long id, @Param("version") Integer version,
              @Param("leaseToken") String leaseToken, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
              @Param("now") LocalDateTime now);

    @Update("UPDATE t_api_delivery SET status = CASE WHEN attempt_count >= max_attempts THEN 'DLQ' ELSE 'RETRYABLE' END, "
            + "retention_class = CASE WHEN attempt_count >= max_attempts THEN 'FAILED_DLQ_PAYLOAD_90D' ELSE retention_class END, "
            + "retention_expires_at = CASE WHEN attempt_count >= max_attempts THEN #{expiry} ELSE retention_expires_at END, "
            + "terminal_at = CASE WHEN attempt_count >= max_attempts THEN #{now} ELSE terminal_at END, "
            + "last_error_code = CASE WHEN attempt_count >= max_attempts THEN 'MAX_ATTEMPTS' ELSE 'LEASE_EXPIRED' END, "
            + "next_attempt_at = #{now}, "
            + "lease_token = NULL, lease_expires_at = NULL, version = version + 1, updated_at = #{now} "
            + "WHERE status = 'CLAIMED' AND lease_expires_at <= #{now}")
    int recoverExpiredLeases(@Param("now") LocalDateTime now, @Param("expiry") LocalDateTime expiry);

    default int recoverExpiredLeases(LocalDateTime now) {
        return recoverExpiredLeases(now, now.plusDays(90));
    }

    @Update("UPDATE t_api_delivery SET status = 'DLQ', last_error_code = 'KILL_SWITCH', "
            + "retention_class = 'FAILED_DLQ_PAYLOAD_90D', retention_expires_at = #{expiry}, "
            + "terminal_at = #{now}, updated_at = #{now}, version = version + 1 "
            + "WHERE id = #{id} AND version = #{version} AND status IN ('PENDING', 'RETRYABLE')")
    int holdPendingAt(@Param("id") Long id, @Param("version") Integer version,
                      @Param("now") LocalDateTime now, @Param("expiry") LocalDateTime expiry);

    default int holdPending(Long id, Integer version, LocalDateTime now) {
        return holdPendingAt(id, version, now, now.plusDays(90));
    }

    @Update("UPDATE t_api_delivery SET status = 'SUCCEEDED', provider_request_id = #{providerRequestId}, "
            + "last_error_code = NULL, terminal_at = #{terminalAt}, retention_class = 'SUCCEEDED_PAYLOAD_30D', "
            + "retention_expires_at = #{retentionExpiresAt}, lease_token = NULL, lease_expires_at = NULL, "
            + "version = version + 1, updated_at = #{terminalAt} WHERE id = #{id} AND version = #{version} "
            + "AND delivery_generation = #{generation} "
            + "AND lease_token = #{leaseToken} AND provider_idempotency_key = #{providerIdempotencyKey} "
            + "AND payload_hash = #{payloadHash} AND status = 'CLAIMED'")
    int transitionSucceeded(@Param("id") Long id, @Param("version") Integer version,
                            @Param("generation") Integer generation,
                            @Param("leaseToken") String leaseToken,
                            @Param("providerIdempotencyKey") String providerIdempotencyKey,
                            @Param("payloadHash") String payloadHash,
                            @Param("providerRequestId") String providerRequestId,
                            @Param("terminalAt") LocalDateTime terminalAt,
                            @Param("retentionExpiresAt") LocalDateTime retentionExpiresAt);

    @Update("UPDATE t_api_delivery SET status = 'RETRYABLE', last_error_code = #{errorCode}, "
            + com.ses.service.integrationhub.IntegrationHubWebhookAttemptAccounting.UNSENT_SQL
            + "next_attempt_at = #{nextAttemptAt}, lease_token = NULL, lease_expires_at = NULL, "
            + "version = version + 1, updated_at = #{now} WHERE id = #{id} AND version = #{version} "
            + "AND delivery_generation = #{generation} "
            + "AND lease_token = #{leaseToken} AND provider_idempotency_key = #{providerIdempotencyKey} "
            + "AND payload_hash = #{payloadHash} AND status = 'CLAIMED'")
    int transitionRetryable(@Param("id") Long id, @Param("version") Integer version,
                            @Param("generation") Integer generation,
                            @Param("leaseToken") String leaseToken,
                            @Param("providerIdempotencyKey") String providerIdempotencyKey,
                            @Param("payloadHash") String payloadHash,
                            @Param("errorCode") String errorCode, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                            @Param("now") LocalDateTime now);

    @Update("UPDATE t_api_delivery SET status = #{status}, last_error_code = #{errorCode}, "
            + com.ses.service.integrationhub.IntegrationHubWebhookAttemptAccounting.UNSENT_SQL
            + "terminal_at = #{terminalAt}, retention_class = 'FAILED_DLQ_PAYLOAD_90D', "
            + "retention_expires_at = #{retentionExpiresAt}, lease_token = NULL, lease_expires_at = NULL, "
            + "version = version + 1, updated_at = #{terminalAt} WHERE id = #{id} AND version = #{version} "
            + "AND delivery_generation = #{generation} "
            + "AND lease_token = #{leaseToken} AND provider_idempotency_key = #{providerIdempotencyKey} "
            + "AND payload_hash = #{payloadHash} AND status = 'CLAIMED' "
            + "AND #{status} IN ('FAILED', 'DLQ')")
    int transitionTerminal(@Param("id") Long id, @Param("version") Integer version,
                           @Param("generation") Integer generation,
                           @Param("leaseToken") String leaseToken,
                           @Param("providerIdempotencyKey") String providerIdempotencyKey,
                           @Param("payloadHash") String payloadHash,
                           @Param("status") String status, @Param("errorCode") String errorCode,
                           @Param("terminalAt") LocalDateTime terminalAt,
                           @Param("retentionExpiresAt") LocalDateTime retentionExpiresAt);

    @Delete("DELETE FROM t_api_delivery WHERE id = #{id} AND version = #{version} "
            + "AND retention_expires_at IS NOT NULL AND retention_expires_at <= #{now} "
            + "AND status IN ('SUCCEEDED', 'FAILED', 'DLQ') "
            + "AND ((lease_token IS NULL AND lease_expires_at IS NULL) "
            + "OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_expires_at <= #{now}))")
    int deleteExpired(@Param("id") Long id, @Param("version") Integer version, @Param("now") LocalDateTime now);
}
