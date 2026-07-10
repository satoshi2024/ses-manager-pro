package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final EngineerMapper engineerMapper;
    private final ContractMapper contractMapper;
    private final ProjectMapper projectMapper;

    @Override
    public DashboardSummaryDto getSummary() {
        // 1. Calculate KPIs
        List<Engineer> allEngineers = engineerMapper.selectList(new QueryWrapper<>());
        long totalEngineers = allEngineers.size();
        
        long activeCount = allEngineers.stream().filter(e -> "稼動中".equals(e.getStatus())).count();
        long benchCount = allEngineers.stream().filter(e -> "Bench".equals(e.getStatus())).count();
        long retiringCount = allEngineers.stream().filter(e -> "退場予定".equals(e.getStatus())).count();
        long proposingCount = allEngineers.stream().filter(e -> "提案中".equals(e.getStatus())).count();

        double utilization = totalEngineers > 0 ? (double) activeCount / totalEngineers * 100 : 0.0;

        List<Contract> activeContracts = contractMapper.selectList(new QueryWrapper<Contract>().eq("status", "稼動中"));
        long totalRevenue = activeContracts.stream().mapToLong(Contract::getSellingPrice).sum();
        long totalCost = activeContracts.stream().mapToLong(Contract::getCostPrice).sum();
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

        // 2. Calculate Charts
        // Mock revenue chart for MVP, we only do status chart correctly
        DashboardSummaryDto.RevenueChartDto revenueChart = DashboardSummaryDto.RevenueChartDto.builder()
                .labels(Arrays.asList("4月", "5月", "6月", "7月", "8月", "9月"))
                .sales(Arrays.asList(3800L, 3950L, 4100L, 4250L, 4300L, totalRevenue))
                .profit(Arrays.asList(1100L, 1150L, 1200L, 1210L, 1250L, grossProfit))
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
}
