package com.ses.dto.bpcompany;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpRiskSummaryDto {
    private long uncheckedComplianceCount;
    private long paymentRiskCount;
    private long lowRatingCount;
    private long suspendedCount;
}
