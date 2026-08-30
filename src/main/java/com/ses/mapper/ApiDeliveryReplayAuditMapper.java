package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiDeliveryReplayAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** NF-05 manual replayのbounded audit mapper。 */
@Mapper
public interface ApiDeliveryReplayAuditMapper extends BaseMapper<ApiDeliveryReplayAudit> {
    @Select("SELECT * FROM t_api_delivery_replay_audit WHERE delivery_id = #{deliveryId} "
            + "AND replay_generation = #{generation} LIMIT 1")
    ApiDeliveryReplayAudit selectByDeliveryGeneration(@Param("deliveryId") Long deliveryId,
                                                       @Param("generation") Integer generation);
}
