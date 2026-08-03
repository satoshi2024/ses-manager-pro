package com.ses.dto.bpcompany;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpCompanyDto {
    private Long id;
    private Long tenantId;
    private String legalName;
    private String nameKana;
    private String entityType;
    private String corporateNumber;
    private String invoiceRegistrationNumber;
    private String capitalBand;
    private String employeeBand;
    private String address;
    private String representative;
    private String status;
    private Integer rating;
    private Long primarySalesUserId;
    private String primarySalesUserName;
    private String complianceApplicability; // FREELANCE_ACT / SUBCOMMITTEE_ACT / EXEMPT / NULL(UNCHECKED)
    private Long applicabilityCheckedBy;
    private String applicabilityCheckedByName;
    private LocalDateTime applicabilityCheckedAt;
    private String applicabilityNote;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
