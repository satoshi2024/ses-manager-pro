package com.ses.dto.invoice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 受信デジタルインボイス review ACCEPT 後に accounting へ渡す仕入候補 DTO（design §5.6）。
 * 自動支払確定は行わない。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundPurchaseRequest {
    private Long digitalInvoiceId;
    private Long supplierCompanyId;
    private BigDecimal amount;
    private LocalDate issueDate;
    private Long purchaseOrderId;
    private Long contractId;
}
