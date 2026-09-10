package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ReportDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

/** 管理レポート配布Mapper。 */
@Mapper
public interface ReportDeliveryMapper extends BaseMapper<ReportDelivery> {

    @Select("SELECT * FROM t_report_delivery WHERE id = #{deliveryId} "
            + "AND deleted_flag = 0 LIMIT 1 FOR UPDATE")
    ReportDelivery selectByIdForReplay(@Param("deliveryId") Long deliveryId);

    @Select("SELECT * FROM t_report_delivery WHERE link_token_hash = #{tokenHash} "
            + "AND deleted_flag = 0 LIMIT 1")
    ReportDelivery selectByLinkTokenHash(@Param("tokenHash") String tokenHash);

    @Select("SELECT * FROM t_report_delivery WHERE notification_outbox_id = #{outboxId} "
            + "AND deleted_flag = 0 LIMIT 1")
    ReportDelivery selectByNotificationOutboxId(@Param("outboxId") Long outboxId);

    @Update("UPDATE t_report_delivery SET delivery_status = #{status}, "
            + "last_error_code = #{errorCode}, last_error_message = #{errorMessage}, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE notification_outbox_id = #{outboxId} "
            + "AND EXISTS (SELECT 1 FROM t_notification_outbox o "
            + "WHERE o.id = t_report_delivery.notification_outbox_id AND o.status = #{status}) "
            + "AND delivery_status IN ('ENQUEUED','PROCESSING','RETRY')")
    int syncOutboxStatus(@Param("outboxId") Long outboxId, @Param("status") String status,
                         @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);
}
