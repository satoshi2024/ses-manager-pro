package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.dashboard.ContractProfitDto;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final EngineerMapper engineerMapper;
    private final ContractMapper contractMapper;
    private final ProjectMapper projectMapper;

    @Override
    public DashboardSummaryDto getSummary(Integer year) {
        // 1. Calculate KPIs
        List<Engineer> allEngineers = engineerMapper.selectList(new QueryWrapper<>());
        long totalEngineers = allEngineers.size();
        
        long activeCount = allEngineers.stream().filter(e -> "稼動中".equals(e.getStatus())).count();
        long benchCount = allEngineers.stream().filter(e -> "Bench".equals(e.getStatus())).count();
        long retiringCount = allEngineers.stream().filter(e -> "退場予定".equals(e.getStatus())).count();
        long proposingCount = allEngineers.stream().filter(e -> "提案中".equals(e.getStatus())).count();

        double utilization = totalEngineers > 0 ? (double) activeCount / totalEngineers * 100 : 0.0;

        List<Contract> activeContracts = contractMapper.selectList(new QueryWrapper<Contract>().eq("status", "稼動中"));
        long totalRevenue = activeContracts.stream().mapToLong(c -> c.getSellingPrice() != null ? c.getSellingPrice().longValue() : 0).sum();
        long totalCost = activeContracts.stream().mapToLong(c -> c.getCostPrice() != null ? c.getCostPrice().longValue() : 0).sum();
        long grossProfit = totalRevenue - totalCost;
        double profitMargin = totalRevenue > 0 ? (double) grossProfit / totalRevenue * 100 : 0.0;

        DashboardSummaryDto.KpiDto kpi = DashboardSummaryDto.KpiDto.builder()
                .utilization(Math.round(utilization * 10.0) / 10.0)
                .utilizationTrend("+0.0%") // Mock trend
                .benchCount((int) benchCount)
                .revenue(totalRevenue)
                .revenueTrend("+0.0%") // Mock trend
                .profitMargin(Math.round(profitMargin * 10.0) / 10.0)
                .profitTrend("-0.0%") // Mock trend
                .build();

        // 2. Calculate Charts (Dynamic)
        List<YearMonth> targetMonths = (year != null)
                ? buildFiscalYearMonths(year)
                : buildTrailingMonths(6);

        List<String> monthLabels = new ArrayList<>();
        List<Long> salesData = new ArrayList<>();
        List<Long> profitData = new ArrayList<>();

        List<Contract> allContracts = contractMapper.selectList(new QueryWrapper<>());

        for (YearMonth ym : targetMonths) {
            LocalDate monthStart = ym.atDay(1);
            LocalDate monthEnd = ym.atEndOfMonth();
            monthLabels.add(ym.getMonthValue() + "月");

            long monthSales = 0;
            long monthProfit = 0;

            for (Contract c : allContracts) {
                if (c.getStartDate() != null && !c.getStartDate().isAfter(monthEnd)) {
                    if (c.getEndDate() == null || !c.getEndDate().isBefore(monthStart)) {
                        long sell = c.getSellingPrice() != null ? c.getSellingPrice().longValue() : 0;
                        long cost = c.getCostPrice() != null ? c.getCostPrice().longValue() : 0;
                        monthSales += sell;
                        monthProfit += (sell - cost);
                    }
                }
            }
            salesData.add(monthSales);
            profitData.add(monthProfit);
        }

        DashboardSummaryDto.RevenueChartDto revenueChart = DashboardSummaryDto.RevenueChartDto.builder()
                .labels(monthLabels)
                .sales(salesData)
                .profit(profitData)
                .build();

        DashboardSummaryDto.StatusChartDto statusChart = DashboardSummaryDto.StatusChartDto.builder()
                .labels(Arrays.asList("稼動中", "Bench", "退場予定", "提案中"))
                .data(Arrays.asList((int)activeCount, (int)benchCount, (int)retiringCount, (int)proposingCount))
                .build();

        DashboardSummaryDto.ChartsDto charts = DashboardSummaryDto.ChartsDto.builder()
                .revenue(revenueChart)
                .status(statusChart)
                .build();

        // 3. Retiring Engineers List
        // Get contracts ending within next 30 days
        LocalDate now = LocalDate.now();
        LocalDate next30Days = now.plusDays(30);
        
        List<Contract> retiringContracts = contractMapper.selectList(new QueryWrapper<Contract>()
                .eq("status", "稼動中")
                .isNotNull("end_date")
                .le("end_date", next30Days));

        List<DashboardSummaryDto.RetiringEngineerDto> retiringList = new ArrayList<>();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd");

        for (Contract c : retiringContracts) {
            Engineer e = engineerMapper.selectById(c.getEngineerId());
            Project p = projectMapper.selectById(c.getProjectId());
            
            if (e != null) {
                int daysLeft = (int) ChronoUnit.DAYS.between(now, c.getEndDate());
                
                DashboardSummaryDto.RetiringEngineerDto dto = DashboardSummaryDto.RetiringEngineerDto.builder()
                        .id(e.getId())
                        .name(e.getFullName())
                        .initial(e.getInitialName())
                        .skill("N/A") // In real system, join with skill table
                        .project(p != null ? p.getProjectName() : "不明")
                        .date(c.getEndDate().format(dtf))
                        .daysLeft(Math.max(0, daysLeft))
                        .proposals(0) // Mock
                        .build();
                retiringList.add(dto);
            }
        }

        return DashboardSummaryDto.builder()
                .kpi(kpi)
                .charts(charts)
                .retiring(retiringList)
                .build();
    }

    private List<YearMonth> buildFiscalYearMonths(int fiscalYear) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth start = YearMonth.of(fiscalYear, 4);
        for (int i = 0; i < 12; i++) {
            months.add(start.plusMonths(i));
        }
        return months;
    }

    private List<YearMonth> buildTrailingMonths(int count) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth current = YearMonth.from(LocalDate.now());
        for (int i = count - 1; i >= 0; i--) {
            months.add(current.minusMonths(i));
        }
        return months;
    }

    @Override
    public List<ContractProfitDto> getProfitAnalysis() {
        List<Contract> contracts = contractMapper.selectList(new QueryWrapper<>());
        List<ContractProfitDto> result = new ArrayList<>();

        for (Contract c : contracts) {
            Engineer e = c.getEngineerId() != null ? engineerMapper.selectById(c.getEngineerId()) : null;
            Project p = c.getProjectId() != null ? projectMapper.selectById(c.getProjectId()) : null;

            int sell = c.getSellingPrice() != null ? c.getSellingPrice().intValue() : 0;
            int cost = c.getCostPrice() != null ? c.getCostPrice().intValue() : 0;
            int grossProfit = sell - cost;
            String rateStr = "N/A";

            if (sell > 0) {
                double rate = (double) grossProfit / sell * 100;
                rateStr = String.format("%.1f%%", rate);
            }

            ContractProfitDto dto = new ContractProfitDto();
            dto.setContractNo(c.getContractNo());
            dto.setEngineerName(e != null ? e.getFullName() : "不明");
            dto.setProjectName(p != null ? p.getProjectName() : "不明");
            dto.setSellingPrice(sell);
            dto.setCostPrice(cost);
            dto.setGrossProfitAmount(grossProfit);
            dto.setGrossProfitRate(rateStr);

            result.add(dto);
        }

        // Sort by StartDate desc
        result.sort((d1, d2) -> {
            Contract c1 = contracts.stream().filter(c -> c.getContractNo().equals(d1.getContractNo())).findFirst().orElse(null);
            Contract c2 = contracts.stream().filter(c -> c.getContractNo().equals(d2.getContractNo())).findFirst().orElse(null);
            LocalDate date1 = c1 != null && c1.getStartDate() != null ? c1.getStartDate() : LocalDate.MIN;
            LocalDate date2 = c2 != null && c2.getStartDate() != null ? c2.getStartDate() : LocalDate.MIN;
            return date2.compareTo(date1);
        });

        return result;
    }
}
