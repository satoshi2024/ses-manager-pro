package com.ses.dto.servicedesk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 顧客ヘルススコア DTO (100点減点モデル: WIP-3)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerHealthScoreDto {
    private Long customerId;
    private String customerName;
    private Integer healthScore;
    private String healthStatus; // HEALTHY, WARNING, CRITICAL

    private Integer openCriticalIssuesCount;
    private Integer slaBreachCount30d;
    private BigDecimal avgCsatScore;
    private Boolean arOverdueFlag;

    private List<String> missingInputs;
    private String factorsExplanation;
    private Map<String, Object> factorBreakdown;
    private LocalDateTime calculatedAt;
}
