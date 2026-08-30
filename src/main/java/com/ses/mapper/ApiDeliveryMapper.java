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
            + "AND next_attempt_at <= #{now}")
    int claim(@Param("id") Long id, @Param("version") Integer version,
              @Param("leaseToken") String leaseToken, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
              @Param("now") LocalDateTime now);

    @Update("UPDATE t_api_delivery SET status = 'RETRYABLE', next_attempt_at = #{now}, "
            + "lease_token = NULL, lease_expires_at = NULL, version = version + 1, updated_at = #{now} "
            + "WHERE status = 'CLAIMED' AND lease_expires_at <= #{now}")
    int recoverExpiredLeases(@Param("now") LocalDateTime now);

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
