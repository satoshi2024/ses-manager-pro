package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_certification_alias")
public class CertificationAlias extends BaseEntity {

    private String tenantId;
    private Long certificationId;
    private String aliasIssuerKey;
    private String aliasNameKey;
    private String normalizedKey;
    private LocalDate validFrom;
    private LocalDate validTo;
    private Long approvedBy;
    private LocalDateTime approvedAt;
}
