package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BP portal向け発注/作業実績DTO（field-inventory §3.2。自社分のみ。他階層・社内情報を含まない）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalBpPaymentDto {
    private Long id;
    private String workMonth;
    private BigDecimal actualHours;
    private BigDecimal amount;
    private String status;
    private LocalDateTime paidDate;
    private LocalDateTime receivedConfirmedAt;
    private String workRecordStatus;
    private String contractNo;
    private String engineerName;
    /** 支払予定日（取引条件から算出。未確定ならnull） */
    private String paymentScheduleDate;
    /** 提出物（請求書/作業報告書）の件数 */
    private long submissionCount;
}
