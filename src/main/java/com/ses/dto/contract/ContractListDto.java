package com.ses.dto.contract;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ContractListDto {
    private Long id;
    private String contractNo;
    private Long engineerId;
    private Long customerId;
    private Long projectId;
    private String contractType;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal sellingPrice;
    private BigDecimal costPrice;
    private String status;

    private String engineerName;
    private String customerName;
    private String projectName;

    private Long salesUserId;
    private String salesUserName;
}
