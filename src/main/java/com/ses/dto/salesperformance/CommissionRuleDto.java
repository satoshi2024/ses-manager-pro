package com.ses.dto.salesperformance;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class CommissionRuleDto {
    private String baseType;
    private BigDecimal rate;
}
