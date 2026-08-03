package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BP銀行口座エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_bp_bank_account")
public class BpBankAccount extends BaseEntity {

    private Long tenantId;
    private Long bpCompanyId;
    private String bankName;
    private String branchName;
    private String accountType; // ORDINARY / CURRENT
    private String encryptedAccountNumber;
    private String accountHolder;
    private String maskedLabel;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String approvalStatus; // PENDING / APPROVED / REJECTED
    private Long approvedBy;
    private LocalDateTime approvedAt;
}
