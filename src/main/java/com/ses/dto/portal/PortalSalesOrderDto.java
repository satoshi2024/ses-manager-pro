package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 顧客portal向け注文請DTO（field-inventory §3.1。受領確認で固定されたsnapshotのみ公開）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalSalesOrderDto {
    private Long id;
    private String orderNo;
    private String customerPoNo;
    private LocalDate orderDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private BigDecimal totalAmountSnapshot;
    private String paymentTermsSnapshot;
    private LocalDateTime createdAt;
    /** 注文請PDFが文書台帳に存在するか（存在時のみdownload可） */
    private boolean acknowledgementAvailable;
}
