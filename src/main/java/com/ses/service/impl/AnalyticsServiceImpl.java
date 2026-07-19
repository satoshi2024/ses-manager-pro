package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.analytics.BenchEngineerDto;
import com.ses.dto.analytics.ContractDateRangeDto;
import com.ses.dto.analytics.EngineerCreatedAtDto;
import com.ses.dto.analytics.UtilizationPointDto;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.dto.engineersales.EngineerPrimarySalesDto;
import com.ses.service.AnalyticsService;
import com.ses.service.SystemConfigService;
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

    private final EngineerMapper engineerMapper;
    private final ContractMapper contractMapper;
    private final EngineerSkillMapper engineerSkillMapper;
    private final EngineerSalesMapper engineerSalesMapper;
    private final SystemConfigService systemConfigService;

    @Override
    public List<UtilizationPointDto> utilizationTrend(int months) {
        // Engineer/Contract の全カラムではなく、集計に必要な列だけを取得する軽量プロジェクション
        // （remarks等の大きな列を含む全件ロードを避け、大量データ時のメモリ使用量を抑える）。
        // ステータス絞り込み（稼動中/終了）もSQL側で行う。
        List<EngineerCreatedAtDto> allEngineers = engineerMapper.selectCreatedAtOnly();
        List<ContractDateRangeDto> allContracts = contractMapper.selectActiveDateRanges();

        List<YearMonth> targetMonths = buildTrailingMonths(months);
        List<UtilizationPointDto> result = new ArrayList<>();

        for (YearMonth ym : targetMonths) {
            LocalDate monthEnd = ym.atEndOfMonth();
            LocalDateTime monthEndDateTime = LocalDateTime.of(monthEnd, LocalTime.of(23, 59, 59));

            Set<Long> activeEngineerIds = new HashSet<>();
            for (ContractDateRangeDto c : allContracts) {
                boolean startedByMonthEnd = !c.getStartDate().isAfter(monthEnd);
                boolean stillActiveAtMonthEnd = c.getEndDate() == null || !c.getEndDate().isBefore(monthEnd);
                if (startedByMonthEnd && stillActiveAtMonthEnd) {
                    activeEngineerIds.add(c.getEngineerId());
                }
            }

            int totalCount = (int) allEngineers.stream()
                    .filter(e -> e.getCreatedAt() != null && !e.getCreatedAt().isAfter(monthEndDateTime))
                    .filter(e -> e.getDeletedFlag() == null || e.getDeletedFlag() == 0 || activeEngineerIds.contains(e.getId()))
                    .count();
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
        
        List<EngineerPrimarySalesDto> primarySales = engineerSalesMapper.selectActivePrimaryByEngineerIds(engineerIds);
        Map<Long, EngineerPrimarySalesDto> primarySalesMap = primarySales.stream()
                .collect(Collectors.toMap(EngineerPrimarySalesDto::getEngineerId, p -> p));

        Map<Long, List<Contract>> contractsByEngineer = contracts.stream()
                .filter(c -> c.getEngineerId() != null)
                .collect(Collectors.groupingBy(Contract::getEngineerId));

        LocalDate today = LocalDate.now();
        List<BenchEngineerDto> result = new ArrayList<>();

        for (Engineer e : benchEngineers) {
            List<Contract> engineerContracts = contractsByEngineer.getOrDefault(e.getId(), Collections.emptyList());

            LocalDate referenceDate = engineerContracts.stream()
                    .filter(c -> c.getStartDate() != null && !c.getStartDate().isAfter(today))
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
            
            EngineerPrimarySalesDto salesDto = primarySalesMap.get(e.getId());
            if (salesDto != null) {
                dto.setPrimarySalesUserId(salesDto.getSalesUserId());
                dto.setPrimarySalesUserName(salesDto.getSalesUserName());
            }
            
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

    @Override
    public com.ses.dto.analytics.AvailabilityTimelineDto getAvailabilityTimeline(String fromMonth, String toMonth, Long skillId, Long salesUserId) {
        LambdaQueryWrapper<Engineer> eqw = new LambdaQueryWrapper<>();
        eqw.eq(Engineer::getDeletedFlag, 0);

        List<Engineer> allEngineers = engineerMapper.selectList(eqw);

        if (skillId != null && !allEngineers.isEmpty()) {
            List<com.ses.entity.EngineerSkill> skills = engineerSkillMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.EngineerSkill>().eq("skill_id", skillId));
            Set<Long> engIds = skills.stream().map(com.ses.entity.EngineerSkill::getEngineerId).collect(Collectors.toSet());
            allEngineers = allEngineers.stream().filter(e -> engIds.contains(e.getId())).collect(Collectors.toList());
        }

        if (salesUserId != null && !allEngineers.isEmpty()) {
            List<Long> currentEngIds = allEngineers.stream().map(Engineer::getId).collect(Collectors.toList());
            List<EngineerPrimarySalesDto> primarySales = engineerSalesMapper.selectActivePrimaryByEngineerIds(currentEngIds);
            Set<Long> engIds = primarySales.stream().filter(s -> salesUserId.equals(s.getSalesUserId())).map(EngineerPrimarySalesDto::getEngineerId).collect(Collectors.toSet());
            allEngineers = allEngineers.stream().filter(e -> engIds.contains(e.getId())).collect(Collectors.toList());
        }

        if (allEngineers.isEmpty()) {
            com.ses.dto.analytics.AvailabilityTimelineDto dto = new com.ses.dto.analytics.AvailabilityTimelineDto();
            dto.setEngineers(Collections.emptyList());
            return dto;
        }

        LocalDate fromDate = com.ses.common.util.DateUtils.parseYearMonth(fromMonth).atDay(1);
        LocalDate toDate = com.ses.common.util.DateUtils.parseYearMonth(toMonth).atEndOfMonth();

        List<Long> engineerIds = allEngineers.stream().map(Engineer::getId).collect(Collectors.toList());
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .in(Contract::getEngineerId, engineerIds)
                .and(wrapper -> wrapper
                        .in(Contract::getStatus, java.util.Arrays.asList("稼動中", "終了"))
                        .le(Contract::getStartDate, toDate)
                        .and(w2 -> w2.isNull(Contract::getEndDate).or().ge(Contract::getEndDate, fromDate))
                )
                .orderByAsc(Contract::getStartDate));

        Map<Long, List<Contract>> contractsByEngineer = contracts.stream()
                .collect(Collectors.groupingBy(Contract::getEngineerId));

        Set<Long> renewedFromContractIds = contracts.stream()
                .filter(c -> c.getRenewedFromContractId() != null)
                .map(Contract::getRenewedFromContractId)
                .collect(Collectors.toSet());

        int noticeDays = systemConfigService.getInt("notice.contract-end-days", 30);
        LocalDate today = LocalDate.now();
        LocalDate noticeThreshold = today.plusDays(noticeDays);

        List<com.ses.dto.analytics.EngineerTimelineDto> timelines = new ArrayList<>();
        for (Engineer eng : allEngineers) {
            com.ses.dto.analytics.EngineerTimelineDto edto = new com.ses.dto.analytics.EngineerTimelineDto();
            edto.setId(eng.getId());
            edto.setName(eng.getFullName());

            List<Contract> engContracts = contractsByEngineer.getOrDefault(eng.getId(), Collections.emptyList());
            List<com.ses.dto.analytics.TimelineBarDto> bars = new ArrayList<>();

            boolean endingSoon = false;

            for (Contract c : engContracts) {
                com.ses.dto.analytics.TimelineBarDto bar = new com.ses.dto.analytics.TimelineBarDto();
                bar.setStart(c.getStartDate());
                bar.setEnd(c.getEndDate());
                bar.setContractId(c.getId());
                bar.setType("contracted");
                bars.add(bar);

                if ("稼動中".equals(c.getStatus())) {
                    if (c.getEndDate() != null && !c.getEndDate().isBefore(today) && !c.getEndDate().isAfter(noticeThreshold)) {
                        if (!renewedFromContractIds.contains(c.getId())) {
                            endingSoon = true;
                        }
                    }
                }
            }

            if ("Bench".equals(eng.getStatus()) || bars.isEmpty()) {
                // Determine available period
                LocalDate availableFrom = eng.getAvailableDate() != null ? eng.getAvailableDate() : today;
                com.ses.dto.analytics.TimelineBarDto bar = new com.ses.dto.analytics.TimelineBarDto();
                bar.setStart(availableFrom);
                bar.setType("available");
                bars.add(bar);
            } else {
                // If last contract has end date, mark available after that
                Contract lastContract = engContracts.get(engContracts.size() - 1);
                if (lastContract.getEndDate() != null && lastContract.getEndDate().isBefore(LocalDate.parse(toMonth + "-01").plusMonths(1).minusDays(1))) {
                    com.ses.dto.analytics.TimelineBarDto bar = new com.ses.dto.analytics.TimelineBarDto();
                    bar.setStart(lastContract.getEndDate().plusDays(1));
                    bar.setType("available");
                    bars.add(bar);
                }
            }

            edto.setBars(bars);
            edto.setEndingSoon(endingSoon);
            timelines.add(edto);
        }

        final java.util.Map<Long, Engineer> engMap = allEngineers.stream().collect(Collectors.toMap(Engineer::getId, e -> e));
        // Sort: endingSoon first, then Bench (status='Bench'), then others
        timelines.sort((t1, t2) -> {
            if (t1.isEndingSoon() != t2.isEndingSoon()) {
                return t1.isEndingSoon() ? -1 : 1;
            }
            Engineer e1 = engMap.get(t1.getId());
            Engineer e2 = engMap.get(t2.getId());
            boolean b1 = e1 != null && "Bench".equals(e1.getStatus());
            boolean b2 = e2 != null && "Bench".equals(e2.getStatus());
            if (b1 != b2) {
                return b1 ? -1 : 1;
            }
            return t1.getName().compareTo(t2.getName());
        });

        com.ses.dto.analytics.AvailabilityTimelineDto result = new com.ses.dto.analytics.AvailabilityTimelineDto();
        result.setEngineers(timelines);
        return result;
    }
}
