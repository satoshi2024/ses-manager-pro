package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.InboundEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** NF-05 inbound event replay/claim mapper。 */
@Mapper
public interface InboundEventMapper extends BaseMapper<InboundEvent> {
    @Select("SELECT * FROM t_inbound_event WHERE client_id = #{clientId} "
            + "AND provider_name = #{providerName} AND provider_event_id = #{providerEventId} LIMIT 1")
    InboundEvent selectByProviderEvent(@Param("clientId") String clientId,
                                       @Param("providerName") String providerName,
                                       @Param("providerEventId") String providerEventId);

    @Select("SELECT * FROM t_inbound_event WHERE client_id = #{clientId} "
            + "AND provider_name = #{providerName} AND provider_event_id = #{providerEventId} FOR UPDATE")
    InboundEvent selectByProviderEventForUpdate(@Param("clientId") String clientId,
                                                @Param("providerName") String providerName,
                                                @Param("providerEventId") String providerEventId);

    @Select("SELECT * FROM t_inbound_event WHERE id = #{id} FOR UPDATE")
    InboundEvent selectForUpdate(@Param("id") Long id);

    @Update("UPDATE t_inbound_event SET status = 'PROCESSING', version = version + 1, updated_at = #{now} "
            + "WHERE id = #{id} AND version = #{version} AND status = 'RECEIVED'")
    int claim(@Param("id") Long id, @Param("version") Integer version, @Param("now") LocalDateTime now);

    @Update("UPDATE t_inbound_event SET status = 'CONFLICT', result_code = 'PAYLOAD_HASH_CONFLICT', "
            + "processed_at = #{now}, terminal_at = #{now}, retention_class = 'FAILED_DLQ_PAYLOAD_90D', "
            + "retention_expires_at = #{retentionExpiresAt}, version = version + 1, updated_at = #{now} "
            + "WHERE id = #{id} AND version = #{version} AND raw_body_hash <> #{rawBodyHash} "
            + "AND status IN ('RECEIVED', 'PROCESSING')")
    int transitionConflict(@Param("id") Long id, @Param("version") Integer version,
                           @Param("rawBodyHash") String rawBodyHash, @Param("now") LocalDateTime now,
                           @Param("retentionExpiresAt") LocalDateTime retentionExpiresAt);

    @Update("UPDATE t_inbound_event SET status = #{status}, result_code = #{resultCode}, "
            + "processed_at = #{terminalAt}, terminal_at = #{terminalAt}, retention_class = #{retentionClass}, "
            + "retention_expires_at = #{retentionExpiresAt}, version = version + 1, updated_at = #{terminalAt} "
            + "WHERE id = #{id} AND version = #{version} AND status = 'PROCESSING' "
            + "AND #{status} IN ('PROCESSED', 'DUPLICATE', 'CONFLICT', 'DLQ')")
    int transitionTerminal(@Param("id") Long id, @Param("version") Integer version,
                           @Param("status") String status, @Param("resultCode") String resultCode,
                           @Param("retentionClass") String retentionClass,
                           @Param("terminalAt") LocalDateTime terminalAt,
                           @Param("retentionExpiresAt") LocalDateTime retentionExpiresAt);

    @Delete("DELETE FROM t_inbound_event WHERE id = #{id} AND version = #{version} "
            + "AND retention_expires_at IS NOT NULL "
            + "AND retention_expires_at <= #{now} AND status IN ('PROCESSED', 'DUPLICATE', 'CONFLICT', 'DLQ')")
    int deleteExpired(@Param("id") Long id, @Param("version") Integer version, @Param("now") LocalDateTime now);
}
