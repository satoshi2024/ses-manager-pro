package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * BP仕入（再委託費用）の標準DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalPurchaseDeal {
    private Long bpPaymentId;
    private Long workRecordId;
    private Long bpCompanyId;
    private String bpCompanyCode;
    private String bpCompanyName;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private BigDecimal amount;
    private BigDecimal taxRate;
    private String accountItemCode;
    private String taxCode;
    private String sectionCode;
    private String remarks;
}
