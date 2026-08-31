package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.InboundEventReplayRequest;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** inbound replay metadataのclaim/CAS/purge mapper。元event purgeをFKで阻害しない。 */
@Mapper
public interface InboundEventReplayRequestMapper extends BaseMapper<InboundEventReplayRequest> {
    @Select("SELECT COALESCE(MAX(replay_generation), 0) FROM t_inbound_event_replay "
            + "WHERE inbound_event_id = #{inboundEventId}")
    Integer selectMaxGeneration(@Param("inboundEventId") Long inboundEventId);

    @Select("SELECT * FROM t_inbound_event_replay WHERE id = #{id} FOR UPDATE")
    InboundEventReplayRequest selectForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_inbound_event_replay WHERE replay_reference = #{replayReference} FOR UPDATE")
    InboundEventReplayRequest selectByReplayReferenceForUpdate(@Param("replayReference") String replayReference);

    @Update("UPDATE t_inbound_event_replay SET status = 'PROCESSING', version = version + 1, "
            + "updated_at = #{now} WHERE id = #{id} AND version = #{version} AND status = 'REQUESTED'")
    int claim(@Param("id") Long id, @Param("version") Integer version, @Param("now") LocalDateTime now);

    @Update("UPDATE t_inbound_event_replay SET status = #{status}, result_code = #{resultCode}, "
            + "processed_at = #{now}, version = version + 1, updated_at = #{now} "
            + "WHERE id = #{id} AND version = #{version} AND status = 'PROCESSING' "
            + "AND #{status} IN ('PROCESSED', 'REJECTED', 'DLQ')")
    int complete(@Param("id") Long id, @Param("version") Integer version,
                 @Param("status") String status, @Param("resultCode") String resultCode,
                 @Param("now") LocalDateTime now);

    @Delete("DELETE FROM t_inbound_event_replay WHERE id = #{id} AND version = #{version} "
            + "AND retention_class = 'AUDIT_METADATA_1Y' AND retention_expires_at <= #{now} "
            + "AND status IN ('PROCESSED', 'REJECTED', 'DLQ')")
    int deleteExpired(@Param("id") Long id, @Param("version") Integer version,
                      @Param("now") LocalDateTime now);
}
