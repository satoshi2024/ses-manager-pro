package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 顧客portal向け請求DTO（field-inventory §3.1。入金済日・消込内部情報を含まない。
 * 入金済状態の変更APIは存在させない: R2.3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalInvoiceDto {
    private Long id;
    private String invoiceNo;
    private String billingMonth;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;
    private BigDecimal taxRate;
    private String status;
    private LocalDate issuedDate;
    private LocalDate dueDate;
    private LocalDateTime receivedConfirmedAt;
    private LocalDate paymentExpectedDate;
    private String portalInquiry;
}
