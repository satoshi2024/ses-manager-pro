package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 永続化された契約compliance finding。rule再実行は一意キーでupsertする。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_finding")
public class ComplianceFinding extends BaseEntity {

    private String tenantId;
    private Long contractId;
    private String code;
    private String severity;
    private String status;
    private String conditionFingerprint;
    private LocalDateTime detectedAt;
    private LocalDate dueDate;
    private Long acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private String resolutionNote;
    private Long evidenceDocumentId;

    @Version
    private Integer version;
}
