package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiDeliveryReplayAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

/** NF-05 manual replayのbounded audit mapper。 */
@Mapper
public interface ApiDeliveryReplayAuditMapper extends BaseMapper<ApiDeliveryReplayAudit> {
    @Select("SELECT * FROM t_api_delivery_replay_audit WHERE delivery_id = #{deliveryId} "
            + "AND replay_generation = #{generation} LIMIT 1")
    ApiDeliveryReplayAudit selectByDeliveryGeneration(@Param("deliveryId") Long deliveryId,
                                                       @Param("generation") Integer generation);

    @Select("SELECT * FROM t_api_delivery_replay_audit WHERE id = #{id} FOR UPDATE")
    ApiDeliveryReplayAudit selectForUpdate(@Param("id") Long id);

    /** delivery削除前に参照を外し、audit行を独立retentionで残す（H2でもON DELETE SET NULLに依存しない）。 */
    @org.apache.ibatis.annotations.Update("UPDATE t_api_delivery_replay_audit SET delivery_id = NULL WHERE delivery_id = #{deliveryId}")
    int clearDeliveryReference(@Param("deliveryId") Long deliveryId);

    @Delete("DELETE FROM t_api_delivery_replay_audit WHERE id = #{id} AND retention_expires_at IS NOT NULL "
            + "AND retention_expires_at <= #{now}")
    int deleteExpired(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);
}
