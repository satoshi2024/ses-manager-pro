package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ReportDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 管理レポート配布Mapper。本番経路は必ずtenant predicate付きメソッドを使う。 */
@Mapper
public interface ReportDeliveryMapper extends BaseMapper<ReportDelivery> {

    @Select("SELECT * FROM t_report_delivery WHERE tenant_id = #{tenantId} AND id = #{deliveryId} "
            + "AND deleted_flag = 0 LIMIT 1 FOR UPDATE")
    ReportDelivery selectByIdForReplay(@Param("tenantId") String tenantId, @Param("deliveryId") Long deliveryId);

    @Select("SELECT * FROM t_report_delivery WHERE tenant_id = #{tenantId} AND link_token_hash = #{tokenHash} "
            + "AND deleted_flag = 0 LIMIT 1")
    ReportDelivery selectByLinkTokenHash(@Param("tenantId") String tenantId, @Param("tokenHash") String tokenHash);

    @Select("SELECT * FROM t_report_delivery WHERE tenant_id = #{tenantId} AND notification_outbox_id = #{outboxId} "
            + "AND deleted_flag = 0 LIMIT 1")
    ReportDelivery selectByNotificationOutboxId(@Param("tenantId") String tenantId, @Param("outboxId") Long outboxId);

    @Update("UPDATE t_report_delivery SET delivery_status = #{status}, "
            + "last_error_code = #{errorCode}, last_error_message = #{errorMessage}, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND notification_outbox_id = #{outboxId} "
            + "AND EXISTS (SELECT 1 FROM t_notification_outbox o "
            + "WHERE o.id = t_report_delivery.notification_outbox_id "
            + "AND o.tenant_id = #{tenantId} AND o.status = #{status}) "
            + "AND delivery_status IN ('ENQUEUED','PROCESSING','RETRY')")
    int syncOutboxStatus(@Param("tenantId") String tenantId, @Param("outboxId") Long outboxId,
                         @Param("status") String status,
                         @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    /*
     * 旧テスト/内部拡張向けの互換委譲。SQLを持たず、必ず明示tenantへ委譲する。
     * 新規コードではtenant引数付きメソッドだけを使用すること。
     */
    @Deprecated
    default ReportDelivery selectByIdForReplay(Long deliveryId) {
        return selectByIdForReplay(
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), deliveryId);
    }

    @Deprecated
    default ReportDelivery selectByLinkTokenHash(String tokenHash) {
        return selectByLinkTokenHash(
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), tokenHash);
    }

    @Deprecated
    default ReportDelivery selectByNotificationOutboxId(Long outboxId) {
        return selectByNotificationOutboxId(
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), outboxId);
    }

    @Deprecated
    default int syncOutboxStatus(Long outboxId, String status, String errorCode, String errorMessage) {
        return syncOutboxStatus(
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(),
                outboxId, status, errorCode, errorMessage);
    }
}
