package com.ses.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class WorkRecordGridDto {
    private Long contractId;
    private String contractNo;
    private String engineerName;
    private String projectName;
    private BigDecimal sellingPrice;
    private BigDecimal costPrice;
    private BigDecimal settlementHoursMin;
    private BigDecimal settlementHoursMax;
    private String fractionRule;
    private String employmentType;
    
    private Long workRecordId;
    private String workMonth;
    private BigDecimal actualHours;
    private BigDecimal billingAmount;
    private BigDecimal paymentAmount;
    private String status;
    private String remarks;
}
