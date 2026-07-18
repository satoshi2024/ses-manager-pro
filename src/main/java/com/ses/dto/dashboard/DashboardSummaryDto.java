package com.ses.dto.dashboard;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryDto {
    private KpiDto kpi;
    private ChartsDto charts;
    private List<RetiringEngineerDto> retiring;

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
