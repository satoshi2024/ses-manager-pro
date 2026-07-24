package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.constant.RenewalState;
import com.ses.common.constant.StatusConstants;
import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.dto.engineersales.EngineerPrimarySalesDto;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.SystemConfigService;
import com.ses.service.UtilizationCalcService;
import com.ses.service.UtilizationForecastService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将来稼働率・Bench予測サービス実装クラス（FR-07）
 */
@Service
@RequiredArgsConstructor
public class UtilizationForecastServiceImpl implements UtilizationForecastService {

    private final EngineerMapper engineerMapper;
    private final ContractMapper contractMapper;
    private final ProjectMapper projectMapper;
    private final CustomerMapper customerMapper;
    private final SysUserMapper sysUserMapper;
    private final EngineerSalesService engineerSalesService;
    private final SystemConfigService systemConfigService;
    private final DataScopeService dataScopeService;
    /** 当月値がダッシュボードKPIと一致することを保証する共通口径サービス(Requirement 1.3)。 */
    private final UtilizationCalcService utilizationCalcService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    @Override
    public UtilizationForecastDto getForecast(int months) {
        int forecastMonths = months > 0 ? Math.min(months, 12) : 3;

        // 1. 権限スコープと対象要員の取得 (Requirement 3.2: DataScopeService に従う)
        List<Engineer> allEngineers = engineerMapper.selectList(new QueryWrapper<>());
        if (dataScopeService.isScoped()) {
            Set<Long> allowedEngineerIds = dataScopeService.allowedEngineerIds();
            allEngineers = allEngineers.stream()
                    .filter(e -> e.getId() != null && allowedEngineerIds.contains(e.getId()))
                    .collect(Collectors.toList());
        }

        Set<Long> engineerIds = allEngineers.stream()
                .map(Engineer::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        YearMonth currentYm = YearMonth.from(LocalDate.now());
        List<YearMonth> targetMonths = new ArrayList<>();
        for (int i = 0; i <= forecastMonths; i++) {
            targetMonths.add(currentYm.plusMonths(i));
        }

        if (engineerIds.isEmpty()) {
            List<UtilizationForecastDto.MonthlyForecastDto> emptyMonthly = targetMonths.stream()
                    .map(ym -> UtilizationForecastDto.MonthlyForecastDto.builder()
                            .month(ym.format(DateTimeFormatter.ofPattern("yyyy/MM")))
                            .yearMonth(ym.toString())
                            .workingCount(0)
                            .benchCount(0)
                            .totalCount(0)
                            .utilizationRate(0.0)
                            .build())
                    .collect(Collectors.toList());

            return UtilizationForecastDto.builder()
                    .monthlyForecasts(emptyMonthly)
                    .rolloffEngineers(Collections.emptyList())
                    .build();
        }

        // 2. 対象要員の契約一覧ロード (共通口径サービスと同一のステータス集合)
        List<Contract> contracts = contractMapper.selectList(
                new QueryWrapper<Contract>()
                        .in("status", UtilizationCalcService.targetContractStatuses())
                        .in("engineer_id", engineerIds)
        );

        Map<Long, List<Contract>> contractsByEngineer = contracts.stream()
                .filter(c -> c.getEngineerId() != null)
                .collect(Collectors.groupingBy(Contract::getEngineerId));

        boolean assumeRenew = UtilizationCalcService.resolveAssumeRenew(systemConfigService);

        // 4. 各月の稼働数・Bench数・稼働率集計(当月 m0 はダッシュボードKPIと同一ロジック)
        List<UtilizationForecastDto.MonthlyForecastDto> monthlyForecasts = new ArrayList<>();
        for (YearMonth ym : targetMonths) {
            UtilizationCalcService.UtilizationSnapshot snapshot =
                    utilizationCalcService.calc(ym, allEngineers, contractsByEngineer, assumeRenew);

            monthlyForecasts.add(UtilizationForecastDto.MonthlyForecastDto.builder()
                    .month(ym.format(DateTimeFormatter.ofPattern("yyyy/MM")))
                    .yearMonth(ym.toString())
                    .workingCount(snapshot.getWorkingCount())
                    .benchCount(snapshot.getBenchCount())
                    .totalCount(snapshot.getTotalCount())
                    .utilizationRate(snapshot.getUtilizationRate())
                    .build());
        }

        // 5. ロールオフ予定要員抽出 (当月以降に契約終了し、翌月Bench化する要員)
        List<UtilizationForecastDto.RolloffEngineerDto> rolloffList = extractRolloffEngineers(
                allEngineers, contractsByEngineer, targetMonths, assumeRenew
        );

        return UtilizationForecastDto.builder()
                .monthlyForecasts(monthlyForecasts)
                .rolloffEngineers(rolloffList)
                .build();
    }

    /**
     * 対象期間内に契約が終了し、Bench化するロールオフ予定要員を抽出
     */
    private List<UtilizationForecastDto.RolloffEngineerDto> extractRolloffEngineers(
            List<Engineer> allEngineers,
            Map<Long, List<Contract>> contractsByEngineer,
            List<YearMonth> targetMonths,
            boolean assumeRenew) {

        List<UtilizationForecastDto.RolloffEngineerDto> result = new ArrayList<>();
        if (allEngineers.isEmpty()) {
            return result;
        }

        // 一時候補 holder
        class RolloffCandidate {
            final Engineer engineer;
            final Contract contract;
            final YearMonth targetMonth;

            RolloffCandidate(Engineer engineer, Contract contract, YearMonth targetMonth) {
                this.engineer = engineer;
                this.contract = contract;
                this.targetMonth = targetMonth;
            }
        }

        List<RolloffCandidate> candidates = new ArrayList<>();

        for (YearMonth ym : targetMonths) {
            LocalDate monthStart = ym.atDay(1);
            LocalDate monthEnd = ym.atEndOfMonth();

            for (Engineer e : allEngineers) {
                List<Contract> engContracts = contractsByEngineer.getOrDefault(e.getId(), Collections.emptyList());

                for (Contract c : engContracts) {
                    if (!StatusConstants.CONTRACT_ACTIVE.equals(c.getStatus())) {
                        continue;
                    }
                    if (c.getEndDate() == null) {
                        continue;
                    }
                    if (c.getEndDate().isBefore(monthStart) || c.getEndDate().isAfter(monthEnd)) {
                        continue;
                    }

                    // 自動更新されない、または明示的に終了判断がされている
                    boolean isExpiring = !Integer.valueOf(1).equals(c.getAutoRenew())
                            || !assumeRenew
                            || RenewalState.DECISION_END.equals(c.getRenewalDecision());

                    if (!isExpiring) {
                        continue;
                    }

                    // 翌月以降に別の有効契約（稼働中・準備中を含む）が無いか確認
                    YearMonth nextMonth = ym.plusMonths(1);
                    boolean activeNextMonth = utilizationCalcService.isActiveInMonth(engContracts, nextMonth, assumeRenew);
                    if (activeNextMonth) {
                        continue; // 翌月も別契約または準備中契約で稼働継続
                    }

                    candidates.add(new RolloffCandidate(e, c, ym));
                }
            }
        }

        if (candidates.isEmpty()) {
            return result;
        }

        // N+1 対策: 実際にロールオフ候補となった ID のみバッチ取得
        List<Long> engineerIds = candidates.stream().map(c -> c.engineer.getId()).distinct().collect(Collectors.toList());
        Map<Long, EngineerPrimarySalesDto> primarySalesMap = engineerSalesService.mapPrimaryByEngineerIds(engineerIds);

        List<Long> projectIds = candidates.stream().map(c -> c.contract.getProjectId()).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        List<Long> customerIds = candidates.stream().map(c -> c.contract.getCustomerId()).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        List<Long> salesUserIds = candidates.stream().map(c -> c.contract.getSalesUserId()).filter(Objects::nonNull).distinct().collect(Collectors.toList());

        Map<Long, Project> projectMap = projectIds.isEmpty() ? Collections.emptyMap() :
                projectMapper.selectBatchIds(projectIds).stream().collect(Collectors.toMap(Project::getId, p -> p));

        Map<Long, Customer> customerMap = customerIds.isEmpty() ? Collections.emptyMap() :
                customerMapper.selectBatchIds(customerIds).stream().collect(Collectors.toMap(Customer::getId, c -> c));

        Map<Long, SysUser> sysUserMap = salesUserIds.isEmpty() ? Collections.emptyMap() :
                sysUserMapper.selectBatchIds(salesUserIds).stream().collect(Collectors.toMap(SysUser::getId, u -> u));

        for (RolloffCandidate cand : candidates) {
            Engineer e = cand.engineer;
            Contract c = cand.contract;
            YearMonth ym = cand.targetMonth;

            Project p = c.getProjectId() != null ? projectMap.get(c.getProjectId()) : null;
            Customer cust = c.getCustomerId() != null ? customerMap.get(c.getCustomerId()) : null;

            Long salesUserId = c.getSalesUserId();
            String salesUserName = null;
            if (salesUserId != null && sysUserMap.containsKey(salesUserId)) {
                salesUserName = sysUserMap.get(salesUserId).getRealName();
            } else {
                EngineerPrimarySalesDto primarySales = primarySalesMap.get(e.getId());
                if (primarySales != null) {
                    salesUserId = primarySales.getSalesUserId();
                    salesUserName = primarySales.getSalesUserName();
                }
            }

            result.add(UtilizationForecastDto.RolloffEngineerDto.builder()
                    .engineerId(e.getId())
                    .engineerName(e.getFullName())
                    .initialName(e.getInitialName())
                    .contractId(c.getId())
                    .contractNo(c.getContractNo())
                    .projectId(c.getProjectId())
                    .projectName(p != null ? p.getProjectName() : "-")
                    .customerId(c.getCustomerId())
                    .customerName(cust != null ? cust.getCompanyName() : "-")
                    .salesUserId(salesUserId)
                    .salesUserName(salesUserName != null ? salesUserName : "-")
                    .endDate(c.getEndDate().format(DATE_FORMATTER))
                    .targetMonth(ym.toString())
                    .autoRenew(c.getAutoRenew())
                    .renewalDecision(c.getRenewalDecision())
                    .build());
        }

        // 終了日順でソート
        result.sort(Comparator.comparing(UtilizationForecastDto.RolloffEngineerDto::getEndDate));
        return result;
    }
}
