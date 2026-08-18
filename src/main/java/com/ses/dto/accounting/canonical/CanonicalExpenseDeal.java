package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 要員経費申請の標準DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalExpenseDeal {
    private Long expenseRequestId;
    private String expenseNo;
    private Long engineerId;
    private String engineerCode;
    private String engineerName;
    private LocalDate expenseDate;
    private String category;
    private BigDecimal amount;
    private BigDecimal taxRate;
    private String accountItemCode;
    private String taxCode;
    private String sectionCode;
    private String description;
}
