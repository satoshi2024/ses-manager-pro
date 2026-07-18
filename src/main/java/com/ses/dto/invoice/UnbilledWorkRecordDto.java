package com.ses.dto.invoice;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class UnbilledWorkRecordDto {
    private Long workRecordId;
    private BigDecimal billingAmount;
    private String engineerName;
    private String projectName;
    /** 全顧客版クエリ(月次締め)のみ設定。顧客単位クエリでは null。 */
    private Long customerId;
}
