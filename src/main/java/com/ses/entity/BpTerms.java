package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;

/**
 * BP取引条件エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_bp_terms")
public class BpTerms extends BaseEntity {

    private Long tenantId;
    private Long bpCompanyId;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Integer closingDay; // 31 = EOM
    private Integer paymentMonthOffset; // 1 = Next month
    private Integer paymentDay; // 30 = 30th
    private String feeBearer; // PAYEE / PAYER
    private String paymentMethod; // BANK_TRANSFER
    /** 受注者（BP）負担を許容する例外理由。手数料控除を有効にするには承認情報とセットが必須。 */
    private String feeBearerExceptionReason;
    private Long feeBearerApprovedBy;
    private java.time.LocalDateTime feeBearerApprovedAt;
    private Integer maxPaymentDays;

    @Version
    private Integer version;
}
