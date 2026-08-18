package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 支払照合・同期DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalPaymentSync {
    private String externalId;
    private String dealId;
    private String partnerCode;
    private String partnerName;
    private LocalDate paymentDate;
    private BigDecimal amount;
    private String status;
    private String referenceNo;
}
