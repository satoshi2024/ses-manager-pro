package com.ses.dto.salesperformance;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class SalesPerformanceDto {
    private Long salesUserId;
    private String salesUserName;
    private Integer activePrimaryCount;
    private Integer closedContractCount;
    private BigDecimal closedRate;
    private Integer activeContractCount;
    private BigDecimal totalSalesAmount;
    private BigDecimal totalProfitAmount;
    private BigDecimal totalCommissionAmount;
}
