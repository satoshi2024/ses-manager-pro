package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_notification")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String type;
    private String title;
    private String message;
    private String linkUrl;
    private String menuKey;
    private Long recipientUserId;
    private String dedupeKey;
    private LocalDateTime createdAt;
}
