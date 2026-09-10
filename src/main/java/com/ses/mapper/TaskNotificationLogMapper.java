package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.TaskNotificationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface TaskNotificationLogMapper extends BaseMapper<TaskNotificationLog> {

    @Update("UPDATE t_task_notification_log SET status = 'CLAIMED', last_error = NULL, next_retry_at = NULL, "
            + "attempt_count = COALESCE(attempt_count, 0) + 1, created_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND task_id = #{taskId} AND notify_date = #{notifyDate} "
            + "AND ((status = 'RETRY' AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP)) "
            + "OR (status = 'CLAIMED' AND created_at < #{staleBefore}))")
    int claimRetry(@Param("tenantId") String tenantId, @Param("taskId") Long taskId,
                   @Param("notifyDate") LocalDate notifyDate,
                   @Param("staleBefore") LocalDateTime staleBefore);

    @Update("UPDATE t_task_notification_log SET status = 'SENT', sent_at = #{sentAt}, last_error = NULL "
            + "WHERE tenant_id = #{tenantId} AND task_id = #{taskId} AND notify_date = #{notifyDate} "
            + "AND status = 'CLAIMED'")
    int markSent(@Param("tenantId") String tenantId, @Param("taskId") Long taskId,
                 @Param("notifyDate") LocalDate notifyDate, @Param("sentAt") LocalDateTime sentAt);

    @Update("UPDATE t_task_notification_log SET status = 'RETRY', last_error = #{error}, "
            + "next_retry_at = #{nextRetryAt} "
            + "WHERE tenant_id = #{tenantId} AND task_id = #{taskId} AND notify_date = #{notifyDate} "
            + "AND status = 'CLAIMED'")
    int markRetry(@Param("tenantId") String tenantId, @Param("taskId") Long taskId,
                  @Param("notifyDate") LocalDate notifyDate, @Param("error") String error,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt);
}
