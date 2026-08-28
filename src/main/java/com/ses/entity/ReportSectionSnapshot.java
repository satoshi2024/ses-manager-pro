package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 管理レポートsection snapshot（t_report_section_snapshot）。run×sectionで不変。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_report_section_snapshot")
public class ReportSectionSnapshot extends BaseEntity {

    private String tenantId;
    private Long runId;
    private String sectionKey;
    private String sectionStatus;
    private String factType;
    private String confirmation;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String cutoffKind;
    private LocalDateTime asOfAt;
    private LocalDateTime dataAsOfAt;
    private String freshnessStatus;
    private String canonicalService;
    private String canonicalDto;
    private String adapterVersion;
    private Long sourceRowCount;
    private String sourceHash;
    private String valueJson;
    private String errorCode;
    private String errorMessage;
    private String snapshotHash;
    private Integer attemptCount;

    @Version
    private Integer version;
}
