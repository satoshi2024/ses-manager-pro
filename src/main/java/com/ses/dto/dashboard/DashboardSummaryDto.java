package com.ses.dto.dashboard;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryDto {
    private KpiDto kpi;
    private ChartsDto charts;
    private List<RetiringEngineerDto> retiring;
    /** 退場予定の全件数（一覧は Top10、すべて見るは同一期間フィルタ用）。 */
    private int retiringTotal;
    /** 退場予定フィルタの開始日 (yyyy-MM-dd)。 */
    private String retiringFrom;
    /** 退場予定フィルタの終了日 (yyyy-MM-dd)。 */
    private String retiringTo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KpiDto {
        private double utilization;
        private String utilizationTrend;
        private int benchCount;
        private long revenue;
        private String revenueTrend;
        private double profitMargin;
        private String profitTrend;
        private String scopeType;
        private String scopeDisplayName;
        /** 未検収売上（円）。検収要・確定済・未検収済の実績合計（R4.3）。 */
        private long unacceptedSales;
        /** 検収平均日数（提出→検収、日単位。R4.3）。 */
        private double avgAcceptanceDays;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChartsDto {
        private RevenueChartDto revenue;
        private StatusChartDto status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevenueChartDto {
        private List<String> labels;
        private List<Long> sales;
        private List<Long> profit;
        private List<Boolean> isActual;
        /** 売上着地予測（無効時 null、有効時は labels と同じ長さ）。 */
        private List<Long> forecast;
        /** 予測の内訳: オープン提案件数（無効時 null）。 */
        private Integer forecastPipelineCount;
        /** 予測の内訳: 月あたり加重合計（無効時 null）。 */
        private Long forecastPipelineAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusChartDto {
        private List<String> labels;
        private List<Integer> data;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetiringEngineerDto {
        private Long id;
        private String name;
        private String initial;
        private String skill;
        private String project;
        private String date;
        private int daysLeft;
        private int proposals;
    }
}
