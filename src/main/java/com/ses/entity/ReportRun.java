package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 管理レポートrun（t_report_run）。runの状態以外の入力は生成時点で固定する。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_report_run")
public class ReportRun extends BaseEntity {

    private String tenantId;
    private String runKey;
    private Long templateId;
    private Long templateVersionId;
    private Long scheduleId;
    private Long regenerationOfRunId;
    private Integer snapshotVersion;
    private String principalType;
    private Long principalUserId;
    private String scopeOwnerType;
    private Long scopeOwnerId;
    private String organizationScopeJson;
    private String scopePolicyVersion;
    private String scopeHash;
    /** 生成時点で確定したrecipient preview hash。配布時に再計算して照合する。 */
    private String recipientPreviewHash;
    /** 生成時点のrecipient ID・role・scope decisionの固定JSON。 */
    private String recipientSnapshotJson;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String cutoffKind;
    private LocalDateTime asOfAt;
    private String timezoneId;
    private LocalDateTime dataAsOfAt;
    private String status;
    private String snapshotSchemaVersion;
    private String sourcePolicyHash;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String failureCode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String failureMessage;
    private LocalDateTime generatedAt;
    private Long createdBy;

    @Version
    private Integer version;
}
