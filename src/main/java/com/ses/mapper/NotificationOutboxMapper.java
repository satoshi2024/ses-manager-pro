package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.NotificationOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 通知外部配信outboxの永続化Mapper。 */
@Mapper
public interface NotificationOutboxMapper extends BaseMapper<NotificationOutbox> {

    @Select("SELECT * FROM t_notification_outbox WHERE id = #{id}")
    NotificationOutbox selectByIdForDispatch(@Param("id") Long id);

    @Select("SELECT * FROM t_notification_outbox "
            + "WHERE status IN ('PENDING','RETRY') "
            + "AND next_attempt_at <= CURRENT_TIMESTAMP "
            + "ORDER BY id LIMIT #{limit}")
    List<NotificationOutbox> selectDue(@Param("limit") int limit);

    @Update("UPDATE t_notification_outbox "
            + "SET status = 'PROCESSING', locked_at = CURRENT_TIMESTAMP, "
            + "attempt_count = COALESCE(attempt_count, 0) + 1 "
            + "WHERE id = #{id} AND status IN ('PENDING','RETRY')")
    int claim(@Param("id") Long id);

    @Update("UPDATE t_notification_outbox "
            + "SET status = 'SENT', sent_at = CURRENT_TIMESTAMP, locked_at = NULL, last_error = NULL "
            + "WHERE id = #{id} AND status = 'PROCESSING'")
    int markSent(@Param("id") Long id);

    @Update("UPDATE t_notification_outbox "
            + "SET status = #{status}, next_attempt_at = #{nextAttemptAt}, "
            + "locked_at = NULL, last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 'PROCESSING'")
    int markResult(@Param("id") Long id, @Param("status") String status,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                   @Param("lastError") String lastError);
}
