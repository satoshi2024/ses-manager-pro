package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_certification")
public class Certification extends BaseEntity {

    private String tenantId;
    private String issuerKey;
    private String externalCodeKey;
    private String nameKey;
    private String identityKey;
    private String displayName;
    private String issuerDisplay;
    private String externalCode;
    private String expiryType;
    private Integer expiryMonths;
    private Integer ruleVersion;
    private Integer activeFlag;
    private Long createdBy;
    private Long updatedBy;
}
