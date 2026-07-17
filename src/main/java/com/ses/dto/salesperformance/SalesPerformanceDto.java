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
    /** 担当営業未設定(sales_user_id IS NULL)契約の合算行フラグ。true の行は担当要員数・成約率・インセンティブ対象外。 */
    private boolean unattributed;
}
