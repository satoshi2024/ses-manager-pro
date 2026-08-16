package com.ses.dto.staffing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 需給heatmap（T078 B1）。
 * skill/role/locationの3次元×月のグリッドと、全社合計行を持つ。
 * 全engineer×全dayの直積は作らず、server aggregateで返す（design §4）。
 * benchCostはHRロールでmask（null）される（design §5.3）。
 */
public class HeatmapDto {

    private LocalDate asOf;
    private List<DimensionRow> skill;
    private List<DimensionRow> role;
    private List<DimensionRow> location;
    /** 全社合計（次元別の内訳合計と一致する）。 */
    private List<MonthCell> totals;

    public HeatmapDto() {
    }

    public HeatmapDto(LocalDate asOf, List<DimensionRow> skill, List<DimensionRow> role,
                      List<DimensionRow> location, List<MonthCell> totals) {
        this.asOf = asOf;
        this.skill = skill;
        this.role = role;
        this.location = location;
        this.totals = totals;
    }

    /** 次元の1行（1グループ）。 */
    public static class DimensionRow {
        private String group;
        private List<MonthCell> cells;

        public DimensionRow() {
        }

        public DimensionRow(String group, List<MonthCell> cells) {
            this.group = group;
            this.cells = cells;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public List<MonthCell> getCells() {
            return cells;
        }

        public void setCells(List<MonthCell> cells) {
            this.cells = cells;
        }
    }

    /** 1月分のセル。FTEは月別FTE（design §5.2の口径）。 */
    public static class MonthCell {
        private String month;
        private BigDecimal demandFte;
        private BigDecimal supplyFte;
        private BigDecimal shortfall;
        private BigDecimal surplus;
        private BigDecimal benchCost;

        public MonthCell() {
        }

        public MonthCell(String month, BigDecimal demandFte, BigDecimal supplyFte,
                         BigDecimal shortfall, BigDecimal surplus, BigDecimal benchCost) {
            this.month = month;
            this.demandFte = demandFte;
            this.supplyFte = supplyFte;
            this.shortfall = shortfall;
            this.surplus = surplus;
            this.benchCost = benchCost;
        }

        public String getMonth() {
            return month;
        }

        public void setMonth(String month) {
            this.month = month;
        }

        public BigDecimal getDemandFte() {
            return demandFte;
        }

        public void setDemandFte(BigDecimal demandFte) {
            this.demandFte = demandFte;
        }

        public BigDecimal getSupplyFte() {
            return supplyFte;
        }

        public void setSupplyFte(BigDecimal supplyFte) {
            this.supplyFte = supplyFte;
        }

        public BigDecimal getShortfall() {
            return shortfall;
        }

        public void setShortfall(BigDecimal shortfall) {
            this.shortfall = shortfall;
        }

        public BigDecimal getSurplus() {
            return surplus;
        }

        public void setSurplus(BigDecimal surplus) {
            this.surplus = surplus;
        }

        public BigDecimal getBenchCost() {
            return benchCost;
        }

        public void setBenchCost(BigDecimal benchCost) {
            this.benchCost = benchCost;
        }
    }

    public LocalDate getAsOf() {
        return asOf;
    }

    public void setAsOf(LocalDate asOf) {
        this.asOf = asOf;
    }

    public List<DimensionRow> getSkill() {
        return skill;
    }

    public void setSkill(List<DimensionRow> skill) {
        this.skill = skill;
    }

    public List<DimensionRow> getRole() {
        return role;
    }

    public void setRole(List<DimensionRow> role) {
        this.role = role;
    }

    public List<DimensionRow> getLocation() {
        return location;
    }

    public void setLocation(List<DimensionRow> location) {
        this.location = location;
    }

    public List<MonthCell> getTotals() {
        return totals;
    }

    public void setTotals(List<MonthCell> totals) {
        this.totals = totals;
    }
}
