package com.ses.dto.contract;

import lombok.Data;

import java.time.LocalDate;

/**
 * 契約更新カレンダーの1件（GET /api/contracts/renewal-calendar）。
 */
@Data
public class RenewalCalendarItemDto {
    private Long contractId;
    private String contractNo;
    private Long engineerId;
    private String engineerName;
    private Long customerId;
    private String customerName;
    private LocalDate endDate;
    private LocalDate renewalDueDate;
    private String status;
    private String renewalState;
    private String renewalDecision;
    private Long salesUserId;
    private String salesUserName;
}
