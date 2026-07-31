package com.ses.dto.bpcompany;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpTermsDto {
    private Long id;
    private Long bpCompanyId;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Integer closingDay;
    private Integer paymentMonthOffset;
    private Integer paymentDay;
    private String feeBearer;
    private String paymentMethod;
    private Integer maxPaymentDays;
    private Integer version;
}
