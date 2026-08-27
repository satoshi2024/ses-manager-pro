package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ReportDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 管理レポート配布Mapper。 */
@Mapper
public interface ReportDeliveryMapper extends BaseMapper<ReportDelivery> {

    @Update("UPDATE t_report_delivery SET delivery_status = #{status}, "
            + "last_error_code = #{errorCode}, last_error_message = #{errorMessage}, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE notification_outbox_id = #{outboxId} "
            + "AND delivery_status IN ('ENQUEUED','PROCESSING','RETRY')")
    int syncOutboxStatus(@Param("outboxId") Long outboxId, @Param("status") String status,
                         @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);
}
