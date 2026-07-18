package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** メール配信履歴。送信結果を非同期処理から失わないための永続化レコード。 */
@Data
@TableName("t_mail_delivery")
public class MailDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String recipient;
    private String subject;
    private String body;
    /** QUEUED / SENT / FAILED / DRY_RUN */
    private String status;
    private Integer attemptCount;
    private String errorMessage;
    private Long invoiceId;
    private LocalDateTime queuedAt;
    private LocalDateTime sentAt;
    private LocalDateTime failedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
