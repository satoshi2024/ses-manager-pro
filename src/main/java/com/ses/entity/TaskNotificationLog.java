package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * タスク期限通知送信ログ（1日1回冪等送信管理）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_task_notification_log")
public class TaskNotificationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long taskId;
    private LocalDate notifyDate;
    private LocalDateTime createdAt;
    /** CLAIMED/SENT/RETRY。送信成功後だけSENTへ遷移する。 */
    private String status;
    private LocalDateTime sentAt;
    private String lastError;
    /** 再送claimの回数。送信成功の証跡ではない。 */
    private Integer attemptCount;
    /** RETRYが再びclaim可能になる時刻。 */
    private LocalDateTime nextRetryAt;
}
