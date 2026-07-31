package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDateTime;

/**
 * BP会社マスタエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_bp_company")
public class BpCompany extends BaseEntity {

    private Long tenantId;
    private String legalName;
    private String nameKana;
    private String entityType; // CORPORATE / INDIVIDUAL / FREELANCE / PROVISIONAL
    private String corporateNumber;
    private String invoiceRegistrationNumber;
    private String capitalBand;
    private String employeeBand;
    private String address;
    private String representative;
    private String status; // ACTIVE / INACTIVE / SUSPENDED / MERGED
    private Integer rating;
    private Long primarySalesUserId;
    private String complianceApplicability; // FREELANCE_ACT / SUBCOMMITTEE_ACT / EXEMPT / NULL(UNCHECKED)
    private Long applicabilityCheckedBy;
    private LocalDateTime applicabilityCheckedAt;
    private String applicabilityNote;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
