package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.NotificationOutbox;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 通知外部配信outboxの永続化Mapper。 */
@Mapper
public interface NotificationOutboxMapper extends BaseMapper<NotificationOutbox> {

    /** 旧呼出し元との互換。tenantはThreadLocalの明示値からのみ解決する。 */
    default NotificationOutbox selectByIdForDispatch(Long id) {
        return selectByIdForDispatch(AccountingTenantContextHolder.requireTenantContext(), id);
    }

    @Select("SELECT * FROM t_notification_outbox WHERE tenant_id = #{tenantId} AND id = #{id}")
    NotificationOutbox selectByIdForDispatch(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Select("SELECT * FROM t_notification_outbox "
            + "WHERE tenant_id = #{tenantId} AND status IN ('PENDING','RETRY') "
            + "AND next_attempt_at <= CURRENT_TIMESTAMP "
            + "ORDER BY id LIMIT #{limit}")
    List<NotificationOutbox> selectDue(@Param("tenantId") String tenantId, @Param("limit") int limit);

    /** 旧呼出し元との互換。未束縛時は例外で停止する。 */
    default List<NotificationOutbox> selectDue(int limit) {
        return selectDue(AccountingTenantContextHolder.requireTenantContext(), limit);
    }

    @Update("UPDATE t_notification_outbox "
            + "SET status = 'PROCESSING', locked_at = CURRENT_TIMESTAMP, "
            + "attempt_count = COALESCE(attempt_count, 0) + 1 "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN ('PENDING','RETRY')")
    int claim(@Param("tenantId") String tenantId, @Param("id") Long id);

    default int claim(Long id) {
        return claim(AccountingTenantContextHolder.requireTenantContext(), id);
    }

    @Update("UPDATE t_notification_outbox "
            + "SET status = 'SENT', sent_at = CURRENT_TIMESTAMP, locked_at = NULL, last_error = NULL "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 'PROCESSING'")
    int markSent(@Param("tenantId") String tenantId, @Param("id") Long id);

    default int markSent(Long id) {
        return markSent(AccountingTenantContextHolder.requireTenantContext(), id);
    }

    @Update("UPDATE t_notification_outbox "
            + "SET status = #{status}, next_attempt_at = #{nextAttemptAt}, "
            + "locked_at = NULL, last_error = #{lastError} "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 'PROCESSING'")
    int markResult(@Param("tenantId") String tenantId, @Param("id") Long id, @Param("status") String status,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                   @Param("lastError") String lastError);

    default int markResult(Long id, String status, LocalDateTime nextAttemptAt, String lastError) {
        return markResult(AccountingTenantContextHolder.requireTenantContext(), id, status,
                nextAttemptAt, lastError);
    }

    /** report deliveryの通常retry用。既存outboxを再利用し、通知行を増やさない。 */
    @Update("UPDATE t_notification_outbox "
            + "SET status = 'PENDING', next_attempt_at = CURRENT_TIMESTAMP, "
            + "locked_at = NULL, last_error = NULL, sent_at = NULL "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN ('RETRY','FAILED')")
    int requeueReport(@Param("tenantId") String tenantId, @Param("id") Long id);

    default int requeueReport(Long id) {
        return requeueReport(AccountingTenantContextHolder.requireTenantContext(), id);
    }

    /** DLQ manual replay用。outboxの試行回数だけを新しい配送世代として再開する。 */
    @Update("UPDATE t_notification_outbox "
            + "SET status = 'PENDING', attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP, "
            + "locked_at = NULL, last_error = NULL, sent_at = NULL "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN ('RETRY','FAILED')")
    int replayReport(@Param("tenantId") String tenantId, @Param("id") Long id);

    default int replayReport(Long id) {
        return replayReport(AccountingTenantContextHolder.requireTenantContext(), id);
    }
}
