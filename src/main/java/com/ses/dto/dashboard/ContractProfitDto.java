package com.ses.dto.dashboard;

import lombok.Data;

@Data
public class ContractProfitDto {
    private String contractNo;
    private String engineerName;
    private String projectName;
    private Long sellingPrice;
    private Long costPrice;
    private Long grossProfitAmount;
    private String grossProfitRate;
    private java.time.LocalDate startDate;
}
