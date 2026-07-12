package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_notification_read")
public class NotificationRead {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long notificationId;
    private Long userId;
    private LocalDateTime readAt;
}
