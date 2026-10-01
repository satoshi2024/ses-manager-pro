package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 添付業務リンク確定失敗の再試行台帳。ファイル内容は保持しない。 */
@Data
@TableName("t_service_attachment_compensation")
public class ServiceRequestAttachmentCompensation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long serviceRequestId;
    private Long commentId;
    private Long documentId;
    private String visibility;
    private String fileName;
    private Long fileSize;
    private String businessKey;
    private String status;
    private Integer attemptCount;
    private String lastError;
    private LocalDateTime nextRetryAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
