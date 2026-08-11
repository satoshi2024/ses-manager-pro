package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_compliance_mapping_version")
public class ComplianceMappingVersion extends BaseEntity {
    private String tenantId;
    private String mappingCode;
    private String mappingVersion;
    private String mappingHash;
    private String reviewPolicyHash;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String status;
    private Integer activeSlot;
    private Integer futureSlot;
    private LocalDateTime activatedAt;
    private Long activatedBy;
    private Long createdBy;
    private Long updatedBy;

    @Version
    private Integer version;
}
