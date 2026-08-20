package com.ses.dto.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class AiEvaluationDashboardDto {
    private int minSegmentCount;
    private boolean costVisible;
    private List<VersionRow> versions = new ArrayList<>();
    private List<Map<String, Object>> reasonDistribution = new ArrayList<>();
    private List<Map<String, Object>> segments = new ArrayList<>();
    private List<Map<String, Object>> samples = new ArrayList<>();

    @Data
    public static class VersionRow {
        private Long versionId;
        private String useCase;
        private String status;
        private String promptVersion;
        private double adoptionRate;
        private double interviewRate;
        private double winRate;
        private double precisionAt5;
        private double precisionAt10;
        private Double latencyP95;
        private Integer costJpy;
        private Integer tokenInput;
        private Integer tokenOutput;
        private long runCount;
    }
}
