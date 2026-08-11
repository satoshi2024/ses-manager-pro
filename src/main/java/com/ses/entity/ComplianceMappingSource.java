package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_compliance_mapping_source")
public class ComplianceMappingSource extends BaseEntity {
    private String tenantId;
    private Long mappingId;
    private String sourceCode;
    private String sourceUrl;
    private String sourceVersion;
    private LocalDate confirmedOn;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Long createdBy;
    private Long updatedBy;
}
