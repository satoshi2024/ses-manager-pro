package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 売上請求の標準DTO (Canonical Model)。
 * freee 取引APIおよび CSV エクスポートの共通入力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalSalesInvoice {
    private Long invoiceId;
    private String invoiceNo;
    private Long customerId;
    private String customerCode;
    private String customerName;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;
    private BigDecimal taxRate;
    private String sectionCode;
    private String remarks;
    private List<Detail> details;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Detail {
        private String description;
        private BigDecimal amount;
        private BigDecimal taxRate;
        private String accountItemCode;
        private String taxCode;
    }
}
