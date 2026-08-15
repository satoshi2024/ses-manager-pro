package com.ses.dto.staffing;

import java.math.BigDecimal;
import java.util.List;

/**
 * 不足セルからのdrilldown（T078 B1）。
 * 指定月×次元×グループに該当する需要側（position）と供給側（engineer）を返す。
 * 単価はHRロールでmask（null）される（design §5.3）。
 */
public class ShortfallDrilldownDto {

    private String month;
    private String dimension;
    private String group;

    private List<PositionLine> positions;
    private List<EngineerLine> engineers;

    public ShortfallDrilldownDto() {
    }

    public ShortfallDrilldownDto(String month, String dimension, String group,
                                 List<PositionLine> positions, List<EngineerLine> engineers) {
        this.month = month;
        this.dimension = dimension;
        this.group = group;
        this.positions = positions;
        this.engineers = engineers;
    }

    /** 需要側（position）。 */
    public static class PositionLine {
        private Long positionId;
        private String positionNo;
        private String roleName;
        private Long projectId;
        private String projectName;
        private Integer requiredCount;
        private String status;
        private BigDecimal unitPriceMin;
        private BigDecimal unitPriceMax;

        public PositionLine() {
        }

        public Long getPositionId() {
            return positionId;
        }

        public void setPositionId(Long positionId) {
            this.positionId = positionId;
        }

        public String getPositionNo() {
            return positionNo;
        }

        public void setPositionNo(String positionNo) {
            this.positionNo = positionNo;
        }

        public String getRoleName() {
            return roleName;
        }

        public void setRoleName(String roleName) {
            this.roleName = roleName;
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

        public Integer getRequiredCount() {
            return requiredCount;
        }

        public void setRequiredCount(Integer requiredCount) {
            this.requiredCount = requiredCount;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public BigDecimal getUnitPriceMin() {
            return unitPriceMin;
        }

        public void setUnitPriceMin(BigDecimal unitPriceMin) {
            this.unitPriceMin = unitPriceMin;
        }

        public BigDecimal getUnitPriceMax() {
            return unitPriceMax;
        }

        public void setUnitPriceMax(BigDecimal unitPriceMax) {
            this.unitPriceMax = unitPriceMax;
        }
    }

    /** 供給側（engineer）。 */
    public static class EngineerLine {
        private Long engineerId;
        private String engineerName;
        private String primarySkill;
        private BigDecimal supplyFte;
        private BigDecimal unitPrice;

        public EngineerLine() {
        }

        public Long getEngineerId() {
            return engineerId;
        }

        public void setEngineerId(Long engineerId) {
            this.engineerId = engineerId;
        }

        public String getEngineerName() {
            return engineerName;
        }

        public void setEngineerName(String engineerName) {
            this.engineerName = engineerName;
        }

        public String getPrimarySkill() {
            return primarySkill;
        }

        public void setPrimarySkill(String primarySkill) {
            this.primarySkill = primarySkill;
        }

        public BigDecimal getSupplyFte() {
            return supplyFte;
        }

        public void setSupplyFte(BigDecimal supplyFte) {
            this.supplyFte = supplyFte;
        }

        public BigDecimal getUnitPrice() {
            return unitPrice;
        }

        public void setUnitPrice(BigDecimal unitPrice) {
            this.unitPrice = unitPrice;
        }
    }

    public String getMonth() {
        return month;
    }

    public void setMonth(String month) {
        this.month = month;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public List<PositionLine> getPositions() {
        return positions;
    }

    public void setPositions(List<PositionLine> positions) {
        this.positions = positions;
    }

    public List<EngineerLine> getEngineers() {
        return engineers;
    }

    public void setEngineers(List<EngineerLine> engineers) {
        this.engineers = engineers;
    }
}
