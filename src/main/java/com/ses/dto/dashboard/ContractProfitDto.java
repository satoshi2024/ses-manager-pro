package com.ses.dto.dashboard;

import lombok.Data;

@Data
public class ContractProfitDto {
    private String contractNo;
    private String engineerName;
    private String projectName;
    private Integer sellingPrice;
    private Integer costPrice;
    private Integer grossProfitAmount;
    private String grossProfitRate;
}
