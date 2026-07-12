package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.analytics.BenchEngineerDto;
import com.ses.dto.analytics.UtilizationPointDto;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final List<String> ACTIVE_CONTRACT_STATUSES = List.of("稼動中", "終了");

    private final EngineerMapper engineerMapper;
    private final ContractMapper contractMapper;
    private final EngineerSkillMapper engineerSkillMapper;

    @Override
    public List<UtilizationPointDto> utilizationTrend(int months) {
        List<Engineer> allEngineers = engineerMapper.selectList(new QueryWrapper<>());
        List<Contract> allContracts = contractMapper.selectList(new QueryWrapper<>());

        List<YearMonth> targetMonths = buildTrailingMonths(months);
        List<UtilizationPointDto> result = new ArrayList<>();

        for (YearMonth ym : targetMonths) {
            LocalDate monthEnd = ym.atEndOfMonth();
            LocalDateTime monthEndDateTime = LocalDateTime.of(monthEnd, LocalTime.of(23, 59, 59));

            int totalCount = (int) allEngineers.stream()
                    .filter(e -> e.getCreatedAt() != null && !e.getCreatedAt().isAfter(monthEndDateTime))
                    .count();

            Set<Long> activeEngineerIds = new HashSet<>();
            for (Contract c : allContracts) {
                if (c.getEngineerId() == null || c.getStartDate() == null) {
                    continue;
                }
                if (!ACTIVE_CONTRACT_STATUSES.contains(c.getStatus())) {
                    continue;
                }
                boolean startedByMonthEnd = !c.getStartDate().isAfter(monthEnd);
                boolean stillActiveAtMonthEnd = c.getEndDate() == null || !c.getEndDate().isBefore(monthEnd);
                if (startedByMonthEnd && stillActiveAtMonthEnd) {
                    activeEngineerIds.add(c.getEngineerId());
                }
            }
            int activeCount = activeEngineerIds.size();
            int benchCount = Math.max(totalCount - activeCount, 0);

            Double utilizationRate = null;
            if (totalCount > 0) {
                double rate = activeCount * 100.0 / totalCount;
                utilizationRate = java.math.BigDecimal.valueOf(rate)
                        .setScale(1, RoundingMode.HALF_UP)
                        .doubleValue();
            }

            UtilizationPointDto dto = new UtilizationPointDto();
            dto.setYearMonth(ym.toString());
            dto.setLabel(ym.getMonthValue() + "月");
            dto.setActiveCount(activeCount);
            dto.setBenchCount(benchCount);
            dto.setTotalCount(totalCount);
            dto.setUtilizationRate(utilizationRate);
            result.add(dto);
        }

        return result;
    }

    @Override
    public List<BenchEngineerDto> benchList() {
        List<Engineer> benchEngineers = engineerMapper.selectList(
                new LambdaQueryWrapper<Engineer>().eq(Engineer::getStatus, "Bench"));

        if (benchEngineers.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> engineerIds = benchEngineers.stream().map(Engineer::getId).collect(Collectors.toList());
        List<Contract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<Contract>().in(Contract::getEngineerId, engineerIds));

        Map<Long, List<Contract>> contractsByEngineer = contracts.stream()
                .filter(c -> c.getEngineerId() != null)
                .collect(Collectors.groupingBy(Contract::getEngineerId));

        LocalDate today = LocalDate.now();
        List<BenchEngineerDto> result = new ArrayList<>();

        for (Engineer e : benchEngineers) {
            List<Contract> engineerContracts = contractsByEngineer.getOrDefault(e.getId(), Collections.emptyList());

            LocalDate referenceDate = engineerContracts.stream()
                    .map(Contract::getEndDate)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElseGet(() -> e.getCreatedAt() != null ? e.getCreatedAt().toLocalDate() : today);

            int benchDays = Math.max(0, (int) ChronoUnit.DAYS.between(referenceDate, today));

            BenchEngineerDto dto = new BenchEngineerDto();
            dto.setEngineerId(e.getId());
            dto.setFullName(e.getFullName());
            dto.setBenchDays(benchDays);
            dto.setExpectedUnitPrice(e.getExpectedUnitPrice());
            dto.setAvailableDate(e.getAvailableDate());
            dto.setSkillNames(resolveSkillNames(e.getId()));
            result.add(dto);
        }

        result.sort(Comparator.comparingInt(BenchEngineerDto::getBenchDays).reversed());
        return result;
    }

    private List<String> resolveSkillNames(Long engineerId) {
        try {
            List<EngineerSkillDetailDto> details = engineerSkillMapper.selectDetailByEngineerId(engineerId);
            if (details == null || details.isEmpty()) {
                return Collections.emptyList();
            }
            return details.stream()
                    .map(EngineerSkillDetailDto::getSkillName)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            // P1(スキル管理)未導入・データ不整合の場合でも分析画面は表示できるようにする
            return Collections.emptyList();
        }
    }

    private List<YearMonth> buildTrailingMonths(int count) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth current = YearMonth.from(LocalDate.now());
        for (int i = count - 1; i >= 0; i--) {
            months.add(current.minusMonths(i));
        }
        return months;
    }
}
