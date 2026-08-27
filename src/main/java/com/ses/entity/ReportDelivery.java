package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 管理レポート配布（t_report_delivery）。recipient scopeとlink状態を固定する。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_report_delivery")
public class ReportDelivery extends BaseEntity {

    private String tenantId;
    private Long runId;
    private Long documentId;
    private Integer documentVersionNo;
    private Long recipientUserId;
    private Long organizationId;
    private String recipientScopeJson;
    private String recipientScopeHash;
    private String previewStatus;
    private LocalDateTime previewedAt;
    private String scopeDecision;
    private String deliveryChannel;
    private String deliveryStatus;
    private String notificationDedupeKey;
    private String linkTokenHash;
    private LocalDateTime linkExpiresAt;
    private Integer reauthRequired;
    private LocalDateTime reauthenticatedAt;
    private Integer attemptCount;
    private LocalDateTime downloadedAt;
    private String lastErrorCode;
    private String lastErrorMessage;

    @Version
    private Integer version;
}
