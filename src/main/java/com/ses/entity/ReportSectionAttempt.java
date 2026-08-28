package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 管理レポートsectionの追記型attempt監査（t_report_section_attempt）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_report_section_attempt")
public class ReportSectionAttempt extends BaseEntity {

    private String tenantId;
    private Long runId;
    private String sectionKey;
    private Integer attemptNo;
    private String sectionStatus;
    private String factType;
    private String confirmation;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String cutoffKind;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime dataAsOfAt;
    private String freshnessStatus;
    private String canonicalService;
    private String canonicalDto;
    private Long sourceRowCount;
    private String sourceHash;
    private String valueJson;
    private String errorCode;
    private String errorMessage;
    private String snapshotHash;
}
