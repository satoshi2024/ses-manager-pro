package com.ses.dto.servicedesk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 顧客ヘルススコア算定結果DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerHealthScoreDto {

    private Long customerId;

    private String customerName;

    /** 総合ヘルススコア (0-100) */
    private Integer healthScore;

    /** ヘルスステータス (HEALTHY >= 80, WARNING 50-79, CRITICAL < 50) */
    private String healthStatus;

    /** 未解決重大障害(P0/P1)件数 */
    private Integer openCriticalIssuesCount;

    /** 直近30日SLA違反件数 */
    private Integer slaBreachCount30d;

    /** 直近90日平均CSATスコア */
    private BigDecimal avgCsatScore;

    /** 売掛金延滞有無フラグ */
    private Boolean arOverdueFlag;

    /** 欠損データ項目リスト (SLA, CSAT, INVOICE, QBR) */
    private List<String> missingInputs;

    /** 算出根拠説明テキスト */
    private String factorsExplanation;

    /** 内訳詳細マップ */
    private Map<String, Object> factorBreakdown;
}
