package com.ses.dto.bpcompany;

import lombok.Data;
import java.time.LocalDate;

/**
 * BP取引条件登録用 DTO (Mass Assignment 防止および承認項目の不法改ざん防止)
 */
@Data
public class BpTermsSaveDto {

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    private Integer closingDay;

    private Integer paymentMonthOffset;

    private Integer paymentDay;

    private String feeBearer;

    private String paymentMethod;

    private String feeBearerExceptionReason;

    private Integer maxPaymentDays;
}
