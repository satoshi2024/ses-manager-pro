package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_engineer_certification")
public class EngineerCertification extends BaseEntity {

    private String tenantId;
    private Long engineerId;
    private Long certificationId;
    private Long continuityGroupId;
    private LocalDate acquiredOn;
    private LocalDate expiresOn;
    private Integer expiryRuleVersion;
    @TableField("certificate_number_encrypted")
    private byte[] certificateNumberEncrypted;
    private String certificateNumberKeyVersion;
    private String certificateNumberCipherFormat;
    private String certificateNumberMasked;
    private String recordState;
    private Integer currentFlag;
    private Long currentHolderKey;
    private Integer revision;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
}
