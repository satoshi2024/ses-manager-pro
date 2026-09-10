package com.ses.dto.contract;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 契約詳細の公開項目。tenant・監査・内部生成元はエンティティから分離する。 */
@Data
public class ContractDetailDto {
    private Long id;
    private String contractNo;
    private Long engineerId;
    private Long projectId;
    private Long positionId;
    private Long customerId;
    private String contractType;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate contractDate;
    private String jobDescription;
    private String workLocation;
    private LocalDate inspectionDueDate;
    private LocalDate paymentDueDate;
    private String paymentMethod;
    private BigDecimal settlementHoursMin;
    private BigDecimal settlementHoursMax;
    private Boolean acceptanceRequired;
    private String acceptanceExemptionReason;
    private BigDecimal sellingPrice;
    private BigDecimal costPrice;
    private String status;
    private String remarks;
    private Boolean directCommandFlag;
    private Integer autoRenew;
    private Long salesUserId;
    private String commissionBaseType;
    private BigDecimal commissionRate;
    private String renewalDecision;
    private Integer version;
}
