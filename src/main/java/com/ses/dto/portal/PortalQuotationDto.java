package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 顧客portal向け見積DTO（field-inventory §3.1。原価・営業memo・内部IDを含まない）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalQuotationDto {
    private Long id;
    private String quotationNo;
    private String title;
    private String status;
    private BigDecimal unitPrice;
    private BigDecimal settlementHoursMin;
    private BigDecimal settlementHoursMax;
    private LocalDate validUntil;
    private String remarks;
    private LocalDateTime createdAt;
}
