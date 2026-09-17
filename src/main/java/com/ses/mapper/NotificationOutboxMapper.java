package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.NotificationOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 通知外部配信outboxの永続化Mapper。本番経路は必ずtenant predicate付きメソッドを使う。 */
@Mapper
public interface NotificationOutboxMapper extends BaseMapper<NotificationOutbox> {

    @Select("SELECT * FROM t_notification_outbox WHERE tenant_id = #{tenantId} AND id = #{id}")
    NotificationOutbox selectByIdForDispatch(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Select("SELECT * FROM t_notification_outbox WHERE tenant_id = #{tenantId} AND dedupe_key = #{dedupeKey} LIMIT 1")
    NotificationOutbox selectByDedupeKey(@Param("tenantId") String tenantId, @Param("dedupeKey") String dedupeKey);

    @Select("SELECT * FROM t_notification_outbox "
            + "WHERE tenant_id = #{tenantId} AND status IN ('PENDING','RETRY') "
            + "AND next_attempt_at <= CURRENT_TIMESTAMP "
            + "ORDER BY id LIMIT #{limit}")
    List<NotificationOutbox> selectDue(@Param("tenantId") String tenantId, @Param("limit") int limit);

    @Select("SELECT * FROM t_notification_outbox "
            + "WHERE tenant_id = #{tenantId} AND status = 'PROCESSING' AND locked_at IS NOT NULL "
            + "AND locked_at < #{staleBefore} ORDER BY id LIMIT #{limit}")
    List<NotificationOutbox> selectStale(@Param("tenantId") String tenantId,
                                         @Param("staleBefore") LocalDateTime staleBefore,
                                         @Param("limit") int limit);

    @Select("SELECT * FROM t_notification_outbox "
            + "WHERE tenant_id = #{tenantId} AND reconciliation_required = 1 ORDER BY id LIMIT #{limit}")
    List<NotificationOutbox> selectReconciliationDue(@Param("tenantId") String tenantId, @Param("limit") int limit);

    @Update("UPDATE t_notification_outbox "
            + "SET status = 'PROCESSING', locked_at = CURRENT_TIMESTAMP, "
            + "attempt_count = COALESCE(attempt_count, 0) + 1 "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN ('PENDING','RETRY')")
    int claim(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Update("UPDATE t_notification_outbox "
            + "SET status = 'SENT', sent_at = CURRENT_TIMESTAMP, locked_at = NULL, last_error = NULL, "
            + "reconciliation_required = 0 "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 'PROCESSING'")
    int markSent(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Update("UPDATE t_notification_outbox "
            + "SET status = #{status}, next_attempt_at = #{nextAttemptAt}, "
            + "locked_at = NULL, last_error = #{lastError}, reconciliation_required = 0 "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 'PROCESSING'")
    int markResult(@Param("tenantId") String tenantId, @Param("id") Long id, @Param("status") String status,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                   @Param("lastError") String lastError);

    @Update("UPDATE t_notification_outbox SET status = 'RETRY', next_attempt_at = #{now}, "
            + "locked_at = NULL, last_error = 'STALE_PROCESSING_RECOVERED', "
            + "reconciliation_required = 0 "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 'PROCESSING' AND locked_at < #{staleBefore}")
    int recoverStale(@Param("tenantId") String tenantId, @Param("id") Long id,
                     @Param("staleBefore") LocalDateTime staleBefore,
                     @Param("now") LocalDateTime now);

    @Update("UPDATE t_notification_outbox SET reconciliation_required = 1, last_error = #{error} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id}")
    int markReconciliationRequired(@Param("tenantId") String tenantId, @Param("id") Long id,
                                   @Param("error") String error);

    @Update("UPDATE t_notification_outbox SET reconciliation_required = 0 "
            + "WHERE tenant_id = #{tenantId} AND id = #{id}")
    int clearReconciliationRequired(@Param("tenantId") String tenantId, @Param("id") Long id);

    /** report deliveryの通常retry用。既存outboxを再利用し、通知行を増やさない。 */
    @Update("UPDATE t_notification_outbox "
            + "SET status = 'PENDING', next_attempt_at = CURRENT_TIMESTAMP, "
            + "locked_at = NULL, last_error = NULL, sent_at = NULL, reconciliation_required = 0 "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN ('RETRY','FAILED')")
    int requeueReport(@Param("tenantId") String tenantId, @Param("id") Long id);

    /** DLQ manual replay用。outboxの試行回数だけを新しい配送世代として再開する。 */
    @Update("UPDATE t_notification_outbox "
            + "SET status = 'PENDING', attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP, "
            + "locked_at = NULL, last_error = NULL, sent_at = NULL, reconciliation_required = 0 "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN ('RETRY','FAILED')")
    int replayReport(@Param("tenantId") String tenantId, @Param("id") Long id);

    /** 未claimのreport通知を取消し、delivery取消後の失効link送信を防ぐ。 */
    @Update("UPDATE t_notification_outbox SET status = 'CANCELLED', locked_at = NULL, "
            + "reconciliation_required = 0, last_error = 'REPORT_DELIVERY_CANCELLED' "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND type = 'MANAGEMENT_REPORT' "
            + "AND status IN ('PENDING','RETRY')")
    int cancelPendingReport(@Param("tenantId") String tenantId, @Param("id") Long id);

    /*
     * 旧テスト/内部拡張向けの互換委譲。SQLを持たず、必ず明示tenantへ委譲する。
     * 新規コードではtenant引数付きメソッドだけを使用すること。
     */
    @Deprecated
    default NotificationOutbox selectByIdForDispatch(Long id) {
        return selectByIdForDispatch(
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), id);
    }

    @Deprecated
    default NotificationOutbox selectByDedupeKey(String dedupeKey) {
        return selectByDedupeKey(
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), dedupeKey);
    }

    @Deprecated
    default List<NotificationOutbox> selectDue(int limit) {
        return selectDue(com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), limit);
    }

    @Deprecated
    default List<NotificationOutbox> selectStale(LocalDateTime staleBefore, int limit) {
        return selectStale(com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(),
                staleBefore, limit);
    }

    @Deprecated
    default List<NotificationOutbox> selectReconciliationDue(int limit) {
        return selectReconciliationDue(
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), limit);
    }

    @Deprecated
    default int claim(Long id) {
        return claim(com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), id);
    }

    @Deprecated
    default int markSent(Long id) {
        return markSent(com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), id);
    }

    @Deprecated
    default int markResult(Long id, String status, LocalDateTime nextAttemptAt, String lastError) {
        return markResult(com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(),
                id, status, nextAttemptAt, lastError);
    }

    @Deprecated
    default int recoverStale(Long id, LocalDateTime staleBefore, LocalDateTime now) {
        return recoverStale(com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(),
                id, staleBefore, now);
    }

    @Deprecated
    default int markReconciliationRequired(Long id, String error) {
        return markReconciliationRequired(
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), id, error);
    }

    @Deprecated
    default int clearReconciliationRequired(Long id) {
        return clearReconciliationRequired(
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), id);
    }

    @Deprecated
    default int requeueReport(Long id) {
        return requeueReport(com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), id);
    }

    @Deprecated
    default int replayReport(Long id) {
        return replayReport(com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext(), id);
    }
}
