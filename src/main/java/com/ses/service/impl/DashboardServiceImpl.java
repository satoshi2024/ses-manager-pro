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
import com.ses.service.SystemConfigService;
import com.ses.service.billing.MonthlyRevenueCalcService;
import com.ses.common.constant.StatusConstants;
import com.ses.entity.Proposal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

import com.ses.service.security.DataScopeService;
import com.ses.service.UtilizationCalcService;

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
    private final SystemConfigService systemConfigService;
    private final DataScopeService dataScopeService;

    /**
     * 組織スコープ。R3.3が dashboard へも同じ母集団を求めるため、KPI・チャート・粗利分析の
     * 契約/要員取得はすべてこの実効母集団を通す。テストスライス互換のため任意注入。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    /** 未検収KPI（R4.3）。テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.AcceptanceMapper acceptanceMapper;
    /** 当月稼働率は将来稼働率予測(FR-07)と同一の共通口径サービスで算出する(Requirement 1.3)。 */
    private final UtilizationCalcService utilizationCalcService;

    @Override
    @org.springframework.cache.annotation.Cacheable(
            cacheNames = com.ses.config.CacheConfig.CACHE_DASHBOARD_SUMMARY,
            keyGenerator = "dashboardScopeKeyGenerator",
            // sync=true: 未キャッシュ時に算出するのは1スレッドだけにし、残りは結果を待つ。
            // これが無いと、起動直後やTTL切れの瞬間に同時アクセス分だけ重い集計が並走し
            // (キャッシュ・スタンピード)、キャッシュ有りの方が遅いという事故になる。
            sync = true)
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

        LocalDate limitDate = currentMonth.atEndOfMonth().plusMonths(1);
        // 売上・粗利もデータスコープに従う。稼働率や要員数だけを絞り込み、金額は全社値を返すと
        // 「自分の担当分の稼働率」と「全社の売上」が同じ画面に並び、担当範囲を限定されたロールに
        // 全社の財務数値が見えてしまう。同一画面の指標は同じ母集団で揃える。
        QueryWrapper<Contract> contractQuery = new QueryWrapper<Contract>()
                .in("status", Arrays.asList("稼動中", "終了", "解約"))
                .le("start_date", limitDate);
        List<Contract> allContracts = scopedContracts(contractQuery);

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

        // 売上着地予測（パイプライン加重）: 確定契約ベース(既存 sales)に、オープン提案の
        // 提示単価×ステージ確率を翌月以降の各月へ加算した別系列を重ねる（実績口径は汚さない）。
        if ("true".equalsIgnoreCase(systemConfigService.getString("forecast.enabled", "true"))) {
            QueryWrapper<Proposal> proposalQuery = new QueryWrapper<Proposal>()
                    .in("status", Arrays.asList(
                            StatusConstants.PROPOSAL_DOCUMENT_SCREENING,
                            StatusConstants.PROPOSAL_FIRST_INTERVIEW,
                            StatusConstants.PROPOSAL_SECOND_INTERVIEW,
                            StatusConstants.PROPOSAL_WAITING_RESULT));
            Set<Long> allowedPipelineEngineerIds = effectiveEngineerIds();
            if (allowedPipelineEngineerIds != null) {
                if (allowedPipelineEngineerIds.isEmpty()) {
                    proposalQuery.apply("1 = 0");
                } else {
                    proposalQuery.in("engineer_id", allowedPipelineEngineerIds);
                }
            }
            List<Proposal> openProposals = proposalMapper.selectList(proposalQuery);
            Map<String, BigDecimal> rates = loadWinRates();
            long pipelinePerMonth = computePipelinePerMonth(openProposals, rates);
            if (!openProposals.isEmpty() && pipelinePerMonth > 0) {
                // 開始月仮定はドラフト規約（成約→翌月1日開始）と同一。
                YearMonth assumedStart = YearMonth.from(LocalDate.now()).plusMonths(1);
                List<Long> forecastData = new ArrayList<>();
                for (int i = 0; i < targetMonths.size(); i++) {
                    YearMonth ym = targetMonths.get(i);
                    long base = salesData.get(i);
                    forecastData.add(!ym.isBefore(assumedStart) ? base + pipelinePerMonth : null);
                }
                revenueChart.setForecast(forecastData);
                revenueChart.setForecastPipelineCount(openProposals.size());
                revenueChart.setForecastPipelineAmount(pipelinePerMonth);
            } else {
                revenueChart.setForecast(null);
                revenueChart.setForecastPipelineCount(0);
                revenueChart.setForecastPipelineAmount(0L);
            }
        }

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

        // 2. Base counts and KPI (Requirement 3.2: DataScopeService / R3.3: 組織scopeに従う)
        Set<Long> allowedEngineerIds = effectiveEngineerIds();
        QueryWrapper<Engineer> engineerQuery = new QueryWrapper<>();
        if (allowedEngineerIds != null) {
            if (allowedEngineerIds.isEmpty()) {
                engineerQuery.apply("1 = 0");
            } else {
                engineerQuery.in("id", allowedEngineerIds);
            }
        }
        List<Engineer> allEngineers = engineerMapper.selectList(engineerQuery);
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

        // 全社稼動率は契約ベースの共通口径サービスで算出する。将来稼働率予測(FR-07)の当月値と
        // 必ず一致させるため、Engineer.status ベースの集計(下のステータス構成チャート)とは口径を分ける。
        Map<Long, List<Contract>> utilizationContractsByEngineer = existingEngineerIds.isEmpty()
                ? Collections.emptyMap()
                : contractMapper.selectList(new QueryWrapper<Contract>()
                        .in("status", UtilizationCalcService.targetContractStatuses())
                        .in("engineer_id", existingEngineerIds))
                    .stream()
                    .filter(c -> c.getEngineerId() != null)
                    .collect(Collectors.groupingBy(Contract::getEngineerId));

        UtilizationCalcService.UtilizationSnapshot utilizationSnapshot = utilizationCalcService.calc(
                currentMonth,
                allEngineers,
                utilizationContractsByEngineer,
                UtilizationCalcService.resolveAssumeRenew(systemConfigService));
        double utilization = utilizationSnapshot.getUtilizationRate();

        // KPI「当月予想売上」「粗利率」はチャート当月値と同一ソース(共通口径の当月 calc 結果)を使用する。
        long totalRevenue = currentAmount.getSales();
        long grossProfit = currentAmount.getProfit();
        double profitMargin = totalRevenue > 0 ? (double) grossProfit / totalRevenue * 100 : 0.0;

        boolean isScoped = dataScopeService.isScoped() ||
                (organizationScopeService != null && !organizationScopeService.hasFullAccess());
        String scopeType = isScoped ? "LIMITED" : "COMPANY";
        String scopeDisplayName = isScoped ? "対象範囲" : "全社";

        // 未検収売上・検収平均日数（R4.3）。母集団は同一のscope（契約ID集合）で揃える。
        long unacceptedSales = 0L;
        double avgAcceptanceDays = 0.0;
        if (acceptanceMapper != null) {
            List<Long> scopeContractIds = kpiScopeContractIds(allContracts);
            java.math.BigDecimal unaccepted = acceptanceMapper.sumUnacceptedSales(scopeContractIds);
            if (unaccepted != null) {
                unacceptedSales = unaccepted.longValue();
            }
            List<com.ses.dto.acceptance.AcceptanceDurationRow> durations =
                    acceptanceMapper.selectAcceptanceDurations(scopeContractIds);
            if (durations != null && !durations.isEmpty()) {
                long totalDays = 0;
                for (com.ses.dto.acceptance.AcceptanceDurationRow row : durations) {
                    if (row.getSubmittedAt() != null && row.getAcceptedAt() != null) {
                        totalDays += java.time.Duration.between(row.getSubmittedAt(), row.getAcceptedAt()).toDays();
                    }
                }
                avgAcceptanceDays = (double) totalDays / durations.size();
            }
        }

        DashboardSummaryDto.KpiDto kpi = DashboardSummaryDto.KpiDto.builder()
                .utilization(Math.round(utilization * 10.0) / 10.0)
                .utilizationTrend(null)
                .benchCount((int) benchCount)
                .revenue(totalRevenue)
                .revenueTrend(revenueTrend)
                .profitMargin(Math.round(profitMargin * 10.0) / 10.0)
                .profitTrend(profitTrend)
                .scopeType(scopeType)
                .scopeDisplayName(scopeDisplayName)
                .unacceptedSales(unacceptedSales)
                .avgAcceptanceDays(Math.round(avgAcceptanceDays * 10.0) / 10.0)
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
            if (retiringList.size() > 10) {
                retiringList = new ArrayList<>(retiringList.subList(0, 10));
            }
        }

        return DashboardSummaryDto.builder()
                .kpi(kpi)
                .charts(charts)
                .retiring(retiringList)
                .build();
    }

    /** ステージ→受注確率(%)のマップを config から解決する。 */
    private Map<String, BigDecimal> loadWinRates() {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        rates.put(StatusConstants.PROPOSAL_DOCUMENT_SCREENING,
                systemConfigService.getDecimal("forecast.win-rate.screening", new BigDecimal("20")));
        rates.put(StatusConstants.PROPOSAL_FIRST_INTERVIEW,
                systemConfigService.getDecimal("forecast.win-rate.first-interview", new BigDecimal("40")));
        rates.put(StatusConstants.PROPOSAL_SECOND_INTERVIEW,
                systemConfigService.getDecimal("forecast.win-rate.second-interview", new BigDecimal("60")));
        rates.put(StatusConstants.PROPOSAL_WAITING_RESULT,
                systemConfigService.getDecimal("forecast.win-rate.awaiting", new BigDecimal("80")));
        return rates;
    }

    /**
     * オープン提案の月あたり加重売上見込み合計を計算する（純関数・テスト容易化のため static）。
     * 提案ごとに 提示単価 × 確率% ÷ 100 を円未満切り捨て（DOWN）で合算する
     * （合算後には丸めない——件数内訳と一致させるため）。
     * NULL 単価・未知ステータス（rates に無い）は 0 として扱う。
     */
    static long computePipelinePerMonth(List<Proposal> openProposals, Map<String, BigDecimal> rates) {
        if (openProposals == null) {
            return 0L;
        }
        long sum = 0L;
        for (Proposal p : openProposals) {
            if (p.getProposedUnitPrice() == null) {
                continue;
            }
            BigDecimal rate = rates.getOrDefault(p.getStatus(), BigDecimal.ZERO);
            sum += p.getProposedUnitPrice()
                    .multiply(rate)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
                    .longValue();
        }
        return sum;
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

    /**
     * データスコープ・組織スコープ適用済みの契約一覧を返す。許可契約が空なら空リスト。
     * KPI・チャート・粗利分析が同じ母集団を見るための共通経路。
     *
     * <p>R3.3 は export/notification と並んで dashboard にも同じ組織scopeを求める。
     * 部門責任者のKPIが全社値のままだと、R4「上長が配下のKPIだけを閲覧できる」が成立しない。
     */
    /** KPIのscope母集団（契約ID）。unscopedならnull=全件。 */
    private List<Long> kpiScopeContractIds(List<Contract> contracts) {
        if (!dataScopeService.isScoped()
                && (organizationScopeService == null || organizationScopeService.hasFullAccess())) {
            return null; // 全件
        }
        if (contracts == null || contracts.isEmpty()) {
            return java.util.List.of();
        }
        return contracts.stream().map(Contract::getId).collect(java.util.stream.Collectors.toList());
    }

    private List<Contract> scopedContracts(QueryWrapper<Contract> query) {
        Set<Long> allowed = effectiveContractIds();
        if (allowed != null) {
            if (allowed.isEmpty()) {
                return Collections.emptyList();
            }
            query.in("id", allowed);
        }
        return contractMapper.selectList(query);
    }

    /**
     * 契約の実効母集団。nullは「制限なし」。組織scopeとDataScopeは同一ID母集団同士の積集合にする
     * （{@code OrganizationScopeService} の結合規則）。
     */
    private Set<Long> effectiveContractIds() {
        Set<Long> dataIds = dataScopeService != null && dataScopeService.isScoped()
                ? dataScopeService.allowedContractIds() : null;
        if (organizationScopeService == null || organizationScopeService.hasFullAccess()) {
            return dataIds;
        }
        return organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedContractIds(LocalDate.now()), dataIds);
    }

    /** 要員の実効母集団。nullは「制限なし」。 */
    private Set<Long> effectiveEngineerIds() {
        Set<Long> dataIds = dataScopeService != null && dataScopeService.isScoped()
                ? dataScopeService.allowedEngineerIds() : null;
        if (organizationScopeService == null || organizationScopeService.hasFullAccess()) {
            return dataIds;
        }
        return organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedEngineerIds(LocalDate.now()), dataIds);
    }

    @Override
    public List<ContractProfitDto> getProfitAnalysis() {
        List<Contract> contracts = scopedContracts(new QueryWrapper<Contract>()
                .in("status", "稼動中", "終了"));
        List<ContractProfitDto> result = new ArrayList<>();

        List<Long> engineerIds = contracts.stream()
                .map(Contract::getEngineerId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        List<Long> projectIds = contracts.stream()
                .map(Contract::getProjectId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Engineer> engineerMap = engineerIds.isEmpty() ? Collections.emptyMap() :
                engineerMapper.selectBatchIds(engineerIds).stream().collect(Collectors.toMap(Engineer::getId, e -> e));
        Map<Long, Project> projectMap = projectIds.isEmpty() ? Collections.emptyMap() :
                projectMapper.selectBatchIds(projectIds).stream().collect(Collectors.toMap(Project::getId, p -> p));

        for (Contract c : contracts) {
            Engineer e = c.getEngineerId() != null ? engineerMap.get(c.getEngineerId()) : null;
            Project p = c.getProjectId() != null ? projectMap.get(c.getProjectId()) : null;

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
