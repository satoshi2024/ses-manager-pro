package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.dashboard.ContractProfitDto;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.DashboardService;
import com.ses.service.billing.MonthlyRevenueCalcService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Collections;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.dto.engineer.EngineerSkillDetailDto;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final EngineerMapper engineerMapper;
    private final ContractMapper contractMapper;
    private final ProjectMapper projectMapper;
    private final WorkRecordMapper workRecordMapper;
    private final EngineerSkillMapper engineerSkillMapper;
    private final ProposalMapper proposalMapper;
    private final MonthlyRevenueCalcService monthlyRevenueCalcService;

    @Override
    public DashboardSummaryDto getSummary(Integer year) {
        // 1. Calculate Charts (Dynamic) and prepare for KPIs
        List<YearMonth> targetMonths = (year != null)
                ? buildFiscalYearMonths(year)
                : buildTrailingMonths(6);

        YearMonth currentMonth = YearMonth.from(LocalDate.now());
        YearMonth previousMonth = currentMonth.minusMonths(1);

        List<YearMonth> queryMonths = new ArrayList<>(targetMonths);
        if (!queryMonths.contains(currentMonth)) queryMonths.add(currentMonth);
        if (!queryMonths.contains(previousMonth)) queryMonths.add(previousMonth);

        List<String> monthStrs = queryMonths.stream().map(YearMonth::toString).collect(Collectors.toList());
        // 確定実績を月別に一括ロードし、月ごとに contract_id -> record へ変換する(共通口径サービスへ渡す形)。
        Map<String, Map<Long, WorkRecord>> confirmedByMonth = workRecordMapper.selectList(
                new QueryWrapper<WorkRecord>().in("work_month", monthStrs).eq("status", "確定")
        ).stream().collect(Collectors.groupingBy(WorkRecord::getWorkMonth,
                Collectors.toMap(WorkRecord::getContractId, w -> w, (w1, w2) -> w1)));

        List<Contract> allContracts = contractMapper.selectList(new QueryWrapper<>());

        List<String> monthLabels = new ArrayList<>();
        List<Long> salesData = new ArrayList<>();
        List<Long> profitData = new ArrayList<>();
        List<Boolean> isActualData = new ArrayList<>();

        for (YearMonth ym : targetMonths) {
            monthLabels.add(ym.getMonthValue() + "月");
            MonthlyRevenueCalcService.MonthlyAmount amount = monthlyRevenueCalcService.calc(
                    ym, allContracts, confirmedByMonth.getOrDefault(ym.toString(), Collections.emptyMap()));
            salesData.add(amount.getSales());
            profitData.add(amount.getProfit());
            isActualData.add(amount.isHasActual());
        }

        DashboardSummaryDto.RevenueChartDto revenueChart = DashboardSummaryDto.RevenueChartDto.builder()
                .labels(monthLabels)
                .sales(salesData)
                .profit(profitData)
                .isActual(isActualData)
                .build();

        // Calculate actual KPI trends (チャート当月値と同一ソース: 共通口径サービス)
        MonthlyRevenueCalcService.MonthlyAmount currentAmount = monthlyRevenueCalcService.calc(
                currentMonth, allContracts, confirmedByMonth.getOrDefault(currentMonth.toString(), Collections.emptyMap()));
        MonthlyRevenueCalcService.MonthlyAmount previousAmount = monthlyRevenueCalcService.calc(
                previousMonth, allContracts, confirmedByMonth.getOrDefault(previousMonth.toString(), Collections.emptyMap()));

        String revenueTrend = null;
        if (previousAmount.getSales() > 0) {
            double rate = (double) (currentAmount.getSales() - previousAmount.getSales()) / (double) previousAmount.getSales() * 100.0;
            revenueTrend = String.format("%+.1f%%", rate);
        }

        String profitTrend = null;
        if (previousAmount.getProfit() > 0) {
            double rate = (double) (currentAmount.getProfit() - previousAmount.getProfit()) / (double) previousAmount.getProfit() * 100.0;
            profitTrend = String.format("%+.1f%%", rate);
        } else if (previousAmount.getProfit() < 0) {
            double rate = (double) (currentAmount.getProfit() - previousAmount.getProfit()) / (double) Math.abs(previousAmount.getProfit()) * 100.0;
            profitTrend = String.format("%+.1f%%", rate);
        }

        List<Contract> activeContracts = allContracts.stream().filter(c -> "稼動中".equals(c.getStatus())).collect(Collectors.toList());

        // 退場予定は Engineer.status ではなく、下部の退場予定リストと同じ契約終了日ベースで集計する。
        LocalDate now = LocalDate.now();
        LocalDate next30Days = now.plusDays(30);
        List<Contract> retiringContracts = new ArrayList<>(activeContracts.stream()
                .filter(c -> c.getEngineerId() != null && c.getEndDate() != null
                        && !c.getEndDate().isBefore(now) && !c.getEndDate().isAfter(next30Days))
                .collect(Collectors.toMap(
                        Contract::getEngineerId,
                        Function.identity(),
                        (first, second) -> first.getEndDate().isBefore(second.getEndDate()) ? first : second,
                        LinkedHashMap::new))
                .values());
        retiringContracts.sort(Comparator.comparing(Contract::getEndDate));
        Set<Long> retiringEngineerIds = retiringContracts.stream()
                .map(Contract::getEngineerId)
                .collect(Collectors.toSet());

        // 2. Base counts and KPI
        List<Engineer> allEngineers = engineerMapper.selectList(new QueryWrapper<>());
        long totalEngineers = allEngineers.size();
        Set<Long> existingEngineerIds = allEngineers.stream()
                .map(Engineer::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        retiringEngineerIds.retainAll(existingEngineerIds);

        long activeCount = allEngineers.stream()
                .filter(e -> "稼動中".equals(e.getStatus()) && !retiringEngineerIds.contains(e.getId()))
                .count();
        long benchCount = allEngineers.stream()
                .filter(e -> "Bench".equals(e.getStatus()) && !retiringEngineerIds.contains(e.getId()))
                .count();
        long retiringCount = retiringEngineerIds.size();
        long proposingCount = allEngineers.stream()
                .filter(e -> "提案中".equals(e.getStatus()) && !retiringEngineerIds.contains(e.getId()))
                .count();

        double utilization = totalEngineers > 0 ? (double) (activeCount + retiringCount) / totalEngineers * 100 : 0.0;

        // KPI「当月予想売上」「粗利率」はチャート当月値と同一ソース(共通口径の当月 calc 結果)を使用する。
        long totalRevenue = currentAmount.getSales();
        long grossProfit = currentAmount.getProfit();
        double profitMargin = totalRevenue > 0 ? (double) grossProfit / totalRevenue * 100 : 0.0;

        DashboardSummaryDto.KpiDto kpi = DashboardSummaryDto.KpiDto.builder()
                .utilization(Math.round(utilization * 10.0) / 10.0)
                .utilizationTrend(null)
                .benchCount((int) benchCount)
                .revenue(totalRevenue)
                .revenueTrend(revenueTrend)
                .profitMargin(Math.round(profitMargin * 10.0) / 10.0)
                .profitTrend(profitTrend)
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
        List<DashboardSummaryDto.RetiringEngineerDto> retiringList = new ArrayList<>();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd");

        if (!retiringEngineerIds.isEmpty()) {
            List<Long> engineerIds = retiringContracts.stream()
                    .map(Contract::getEngineerId)
                    .filter(retiringEngineerIds::contains)
                    .distinct()
                    .collect(Collectors.toList());
            List<Long> projectIds = retiringContracts.stream()
                    .filter(c -> retiringEngineerIds.contains(c.getEngineerId()))
                    .map(Contract::getProjectId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            Map<Long, Engineer> engineerMap = engineerIds.isEmpty() ? Collections.emptyMap() :
                    engineerMapper.selectBatchIds(engineerIds).stream().collect(Collectors.toMap(Engineer::getId, e -> e));
            Map<Long, Project> projectMap = projectIds.isEmpty() ? Collections.emptyMap() : projectMapper.selectBatchIds(projectIds).stream().collect(Collectors.toMap(Project::getId, p -> p));

            List<EngineerSkillDetailDto> topSkills = engineerSkillMapper.selectTopSkillCandidates(engineerIds);
            Map<Long, String> topSkillMap = topSkills.stream()
                    .collect(Collectors.toMap(EngineerSkillDetailDto::getEngineerId, EngineerSkillDetailDto::getSkillName, (s1, s2) -> s1));

            Map<Long, Long> proposingMap = proposalMapper.selectList(new QueryWrapper<com.ses.entity.Proposal>()
                    .in("engineer_id", engineerIds)
                    .notIn("status", "成約", "見送り")).stream()
                    .collect(Collectors.groupingBy(com.ses.entity.Proposal::getEngineerId, Collectors.counting()));

            for (Contract c : retiringContracts) {
                Engineer e = engineerMap.get(c.getEngineerId());
                Project p = projectMap.get(c.getProjectId());
                
                if (e != null) {
                    int daysLeft = (int) ChronoUnit.DAYS.between(now, c.getEndDate());
                    String skill = topSkillMap.getOrDefault(e.getId(), "-");
                    long proposals = proposingMap.getOrDefault(e.getId(), 0L);
                    
                    DashboardSummaryDto.RetiringEngineerDto dto = DashboardSummaryDto.RetiringEngineerDto.builder()
                            .id(e.getId())
                            .name(e.getFullName())
                            .initial(e.getInitialName())
                            .skill(skill)
                            .project(p != null ? p.getProjectName() : "不明")
                            .date(c.getEndDate().format(dtf))
                            .daysLeft(Math.max(0, daysLeft))
                            .proposals((int) proposals)
                            .build();
                    retiringList.add(dto);
                }
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
            dto.setStartDate(c.getStartDate());

            result.add(dto);
        }

        // Sort by StartDate desc
        result.sort(Comparator.comparing(ContractProfitDto::getStartDate,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return result;
    }
}
