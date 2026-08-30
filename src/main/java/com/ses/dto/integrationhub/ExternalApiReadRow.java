package com.ses.dto.integrationhub;

import lombok.Data;

import java.time.LocalDate;

/** 外部read queryの内部projection。controllerのJSONへ直接渡さない。 */
@Data
public class ExternalApiReadRow {
    private Long id;
    private String status;
    private LocalDate availableDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private Long customerId;
    private Long projectId;
    private Long contractId;
    private String renewalStatus;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private LocalDate paidDate;
}
