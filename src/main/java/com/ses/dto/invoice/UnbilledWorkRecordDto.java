package com.ses.dto.invoice;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class UnbilledWorkRecordDto {
    private Long workRecordId;
    private BigDecimal billingAmount;
    private String engineerName;
    private String projectName;
}
