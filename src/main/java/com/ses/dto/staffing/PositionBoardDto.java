package com.ses.dto.staffing;

import com.ses.entity.ProjectPosition;

import java.util.List;

/**
 * 案件詳細のポジションボード（T077 A1）。
 * 案件配下のposition列と、各positionの充足人数・配置カード（actual+plan）を持つ。
 * 集計はservice側で行い、全engineer×全dayの直積を画面へ返さない。
 */
public class PositionBoardDto {

    private Long projectId;
    private String projectName;
    private List<PositionColumnDto> columns;
    /** ポジション未紐付き（社内/待機）の配置カード */
    private List<AllocationCardDto> benchAllocations;

    public PositionBoardDto() {
    }

    public PositionBoardDto(Long projectId, String projectName,
                            List<PositionColumnDto> columns, List<AllocationCardDto> benchAllocations) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.columns = columns;
        this.benchAllocations = benchAllocations;
    }

    /** ポジション1列。 */
    public static class PositionColumnDto {
        private ProjectPosition position;
        private int filledCount;
        private List<AllocationCardDto> allocations;

        public PositionColumnDto() {
        }

        public PositionColumnDto(ProjectPosition position, int filledCount, List<AllocationCardDto> allocations) {
            this.position = position;
            this.filledCount = filledCount;
            this.allocations = allocations;
        }

        public ProjectPosition getPosition() {
            return position;
        }

        public void setPosition(ProjectPosition position) {
            this.position = position;
        }

        /** 充足人数（actual=契約由来の確定配置の数）。 */
        public int getFilledCount() {
            return filledCount;
        }

        public void setFilledCount(int filledCount) {
            this.filledCount = filledCount;
        }

        public List<AllocationCardDto> getAllocations() {
            return allocations;
        }

        public void setAllocations(List<AllocationCardDto> allocations) {
            this.allocations = allocations;
        }
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public List<PositionColumnDto> getColumns() {
        return columns;
    }

    public void setColumns(List<PositionColumnDto> columns) {
        this.columns = columns;
    }

    public List<AllocationCardDto> getBenchAllocations() {
        return benchAllocations;
    }

    public void setBenchAllocations(List<AllocationCardDto> benchAllocations) {
        this.benchAllocations = benchAllocations;
    }
}
