package com.ses.dto.crm;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** CRM KPIの画面用集計DTO。既存dashboard forecastとは別系列で返す。 */
@Data
public class CrmKpiDto {
    private List<StageKpi> stages;
    private List<OwnerKpi> owners;
    private List<LostReasonKpi> lostReasons;
    private List<SourceRoiKpi> sources;
    private ForecastKpi forecast;

    @Data
    public static class StageKpi {
        private String stage;
        private long count;
        private BigDecimal amount;
        private BigDecimal weightedAmount;
        private long averageStagnationDays;
        private long averageNoActivityDays;
    }

    @Data
    public static class OwnerKpi {
        private Long ownerUserId;
        private String ownerName;
        private long leadCount;
        private long convertedLeadCount;
        private BigDecimal leadConversionRate;
        private long opportunityCount;
        private long wonCount;
        private long lostCount;
        private BigDecimal opportunityConversionRate;
    }

    @Data
    public static class LostReasonKpi {
        private String reason;
        private long count;
        private BigDecimal amount;
    }

    @Data
    public static class SourceRoiKpi {
        private String source;
        private long leadCount;
        private long convertedLeadCount;
        private long wonCount;
        private BigDecimal pipelineAmount;
        private BigDecimal wonAmount;
        private BigDecimal roiRate;
    }

    @Data
    public static class ForecastKpi {
        private BigDecimal opportunityAmount;
        private long opportunityCount;
        private BigDecimal proposalAmount;
        private long proposalCount;
    }
}
