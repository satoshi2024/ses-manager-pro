package com.ses.dto.servicedesk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 顧客ヘルススコア DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerHealthScoreDto {
    private Long customerId;
    private String customerName;
    private Integer healthScore;
    private String healthStatus; // HEALTHY, NEUTRAL, AT_RISK

    private Double slaComplianceScore; // max 30
    private Double csatScore;          // max 25
    private Double engagementScore;    // max 25
    private Double communicationScore; // max 20

    private Map<String, Object> factorBreakdown;
    private LocalDateTime calculatedAt;
}
