package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 資格取得recordのappend-only event（t_certification_event）。
 */
@Data
@TableName("t_certification_event")
public class CertificationEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long certificationRecordId;
    private String eventType;
    private Long supersedesEventId;
    private String reason;
    private Long actorUserId;
    private String actorRoleSnapshot;
    private LocalDateTime occurredAt;
    private String effectiveRecordState;
    private LocalDate effectiveAcquiredOn;
    private LocalDate effectiveExpiresOn;
    private Long evidenceDocumentId;
    private Long evidenceDocumentVersionId;
    private String evidenceDocumentHash;
    private LocalDateTime createdAt;
}
