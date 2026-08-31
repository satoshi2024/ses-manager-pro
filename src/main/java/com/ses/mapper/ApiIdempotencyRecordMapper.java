package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiIdempotencyRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** NF-05 idempotency digest mapper。 */
@Mapper
public interface ApiIdempotencyRecordMapper extends BaseMapper<ApiIdempotencyRecord> {
    @Select("SELECT * FROM t_api_idempotency_record WHERE id = #{id} FOR UPDATE")
    ApiIdempotencyRecord selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_api_idempotency_record WHERE client_id = #{clientId} "
            + "AND route_template = #{routeTemplate} AND idempotency_key = #{idempotencyKey} LIMIT 1")
    ApiIdempotencyRecord selectByNaturalKey(@Param("clientId") String clientId,
                                            @Param("routeTemplate") String routeTemplate,
                                            @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM t_api_idempotency_record WHERE client_id = #{clientId} "
            + "AND route_template = #{routeTemplate} AND idempotency_key = #{idempotencyKey} FOR UPDATE")
    ApiIdempotencyRecord selectByNaturalKeyForUpdate(@Param("clientId") String clientId,
                                                     @Param("routeTemplate") String routeTemplate,
                                                     @Param("idempotencyKey") String idempotencyKey);

    @Update("UPDATE t_api_idempotency_record SET status = 'SUCCEEDED', response_status = #{responseStatus}, "
            + "safe_response_snapshot = #{safeResponseSnapshot}, terminal_at = #{terminalAt}, "
            + "retention_class = 'SUCCEEDED_PAYLOAD_30D', retention_expires_at = #{retentionExpiresAt}, "
            + "version = version + 1, updated_at = #{terminalAt} WHERE id = #{id} AND version = #{version} "
            + "AND request_digest = #{requestDigest} AND status = 'IN_PROGRESS'")
    int transitionSucceeded(@Param("id") Long id, @Param("version") Integer version,
                            @Param("requestDigest") String requestDigest, @Param("responseStatus") Integer responseStatus,
                            @Param("safeResponseSnapshot") String safeResponseSnapshot,
                            @Param("terminalAt") LocalDateTime terminalAt,
                            @Param("retentionExpiresAt") LocalDateTime retentionExpiresAt);

    @Update("UPDATE t_api_idempotency_record SET status = 'FAILED', response_status = #{responseStatus}, "
            + "safe_response_snapshot = #{safeResponseSnapshot}, terminal_at = #{terminalAt}, "
            + "retention_class = 'FAILED_DLQ_PAYLOAD_90D', retention_expires_at = #{retentionExpiresAt}, "
            + "version = version + 1, updated_at = #{terminalAt} WHERE id = #{id} AND version = #{version} "
            + "AND request_digest = #{requestDigest} AND status = 'IN_PROGRESS'")
    int transitionFailed(@Param("id") Long id, @Param("version") Integer version,
                         @Param("requestDigest") String requestDigest, @Param("responseStatus") Integer responseStatus,
                         @Param("safeResponseSnapshot") String safeResponseSnapshot,
                         @Param("terminalAt") LocalDateTime terminalAt,
                            @Param("retentionExpiresAt") LocalDateTime retentionExpiresAt);

    @Update("UPDATE t_api_idempotency_record SET status = 'CONFLICT', response_status = 409, "
            + "safe_response_snapshot = '{\"code\":\"IDEMPOTENCY_PAYLOAD_CONFLICT\"}', "
            + "terminal_at = #{terminalAt}, retention_class = 'FAILED_DLQ_PAYLOAD_90D', "
            + "retention_expires_at = #{retentionExpiresAt}, version = version + 1, updated_at = #{terminalAt} "
            + "WHERE id = #{id} AND version = #{version} AND request_digest = #{requestDigest} "
            + "AND status = 'IN_PROGRESS'")
    int transitionConflict(@Param("id") Long id, @Param("version") Integer version,
                           @Param("requestDigest") String requestDigest,
                           @Param("terminalAt") LocalDateTime terminalAt,
                           @Param("retentionExpiresAt") LocalDateTime retentionExpiresAt);

    @Delete("DELETE FROM t_api_idempotency_record WHERE id = #{id} AND version = #{version} "
            + "AND retention_expires_at IS NOT NULL AND retention_expires_at <= #{now} "
            + "AND status IN ('SUCCEEDED', 'FAILED', 'CONFLICT')")
    int deleteExpired(@Param("id") Long id, @Param("version") Integer version, @Param("now") LocalDateTime now);
}
