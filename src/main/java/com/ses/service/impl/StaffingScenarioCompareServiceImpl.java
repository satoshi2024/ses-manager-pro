package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.staffing.AllocationCardDto;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Engineer;
import com.ses.entity.ProjectPosition;
import com.ses.entity.StaffingScenario;
import com.ses.entity.StaffingScenarioAllocation;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.mapper.StaffingScenarioAllocationMapper;
import com.ses.mapper.StaffingScenarioMapper;
import com.ses.service.security.DataScopeService;
import com.ses.service.staffing.StaffingClock;
import com.ses.service.staffing.StaffingScenarioCompareService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 仮配置シナリオ比較の実装（T079 B2）。
 *
 * <p>本serviceはt_staffing_scenario系テーブルのみを読み、実データへ書き込む経路を持たない。
 * 共有scenarioの閲覧時は閲覧者のscope（DataScope/組織scope）で要員をfilterする（design §5.3）。
 */
@Service
@RequiredArgsConstructor
public class StaffingScenarioCompareServiceImpl implements StaffingScenarioCompareService {

    private static final String ROLE_HR = "HR";

    private final StaffingScenarioMapper scenarioMapper;
    private final StaffingScenarioAllocationMapper scenarioAllocationMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectPositionMapper positionMapper;
    private final DataScopeService dataScopeService;
    private final StaffingClock clock;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ScenarioMonthDto> compare(List<Long> scenarioIds, LocalDate asOf) {
        if (scenarioIds == null || scenarioIds.isEmpty()) {
            throw BusinessException.of(400, "error.staffing.scenarioNotFound");
        }
        boolean maskCost = ROLE_HR.equals(SecurityUtils.currentRole());

        YearMonth from = YearMonth.from(asOf);
        YearMonth to = from.plusMonths(StaffingClock.HORIZON_MONTHS - 1);

        List<ScenarioMonthDto> result = new ArrayList<>();
        for (Long scenarioId : scenarioIds) {
            StaffingScenario scenario = scenarioMapper.selectById(scenarioId);
            if (scenario == null) {
                throw BusinessException.of(404, "error.staffing.scenarioNotFound");
            }
            requireVisible(scenario);
            // 閲覧者scopeで要員をfilterする（SQL境界・scenario経由のscope迂回を防ぐ）
            List<StaffingScenarioAllocation> scoped = scopedAllocations(scenarioId);
            Map<Long, Engineer> engineers = loadEngineers(scoped);
            Map<Long, ProjectPosition> positions = loadPositions(scoped);

            for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
                MonthAgg agg = aggregate(scenario.getId(), scoped, engineers, positions, month);
                result.add(new ScenarioMonthDto(scenario.getId(), scenario.getName(), month,
                        agg.engineerCount, agg.supplyFte,
                        agg.engineerCount == 0 ? 0.0
                                : agg.supplyFte.doubleValue() / agg.engineerCount * 100.0,
                        maskCost ? null : agg.grossProfit));
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AllocationCardDto> visibleAllocations(Long scenarioId) {
        StaffingScenario scenario = scenarioMapper.selectById(scenarioId);
        if (scenario == null) {
            throw BusinessException.of(404, "error.staffing.scenarioNotFound");
        }
        requireVisible(scenario);
        // 閲覧者scopeで要員をfilterする（SQL境界）
        List<StaffingScenarioAllocation> scoped = scopedAllocations(scenarioId);
        Map<Long, Engineer> engineers = loadEngineers(scoped);
        Map<Long, ProjectPosition> positions = loadPositions(scoped);

        List<AllocationCardDto> cards = new ArrayList<>();
        for (StaffingScenarioAllocation allocation : scoped) {
            AllocationCardDto card = new AllocationCardDto();
            card.setId(allocation.getId());
            card.setEngineerId(allocation.getEngineerId());
            Engineer engineer = engineers.get(allocation.getEngineerId());
            card.setEngineerName(engineer == null ? null : engineer.getFullName());
            card.setPositionId(allocation.getPositionId());
            ProjectPosition position = allocation.getPositionId() == null
                    ? null : positions.get(allocation.getPositionId());
            if (position != null) {
                card.setPositionNo(position.getPositionNo());
                card.setRoleName(position.getRoleName());
                card.setProjectId(position.getProjectId());
            }
            card.setAllocationType(position == null
                    ? AllocationPlan.TYPE_INTERNAL : AllocationPlan.TYPE_PROJECT);
            card.setAllocationPercent(allocation.getPercent());
            card.setStartDate(firstDateOf(allocation.getDates()));
            card.setEndDate(lastDateOf(allocation.getDates()));
            cards.add(card);
        }
        return cards;
    }

    // ---------------------------------------------------------------

    /**
     * 閲覧者のscope（DataScope/組織scope）で要員をSQL境界でfilterした仮配置一覧。
     * null=全件、空集合はDB側で0件（platform-invariants §2.2）。
     */
    private List<StaffingScenarioAllocation> scopedAllocations(Long scenarioId) {
        Set<Long> allowed = dataScopeService.isScoped() ? dataScopeService.allowedEngineerIds() : null;
        LambdaQueryWrapper<StaffingScenarioAllocation> query =
                new LambdaQueryWrapper<StaffingScenarioAllocation>()
                        .eq(StaffingScenarioAllocation::getScenarioId, scenarioId);
        if (allowed != null) {
            if (allowed.isEmpty()) {
                query.apply("1 = 0");
            } else {
                query.in(StaffingScenarioAllocation::getEngineerId, allowed);
            }
        }
        return scenarioAllocationMapper.selectList(query.orderByAsc(StaffingScenarioAllocation::getId));
    }

    private MonthAgg aggregate(Long scenarioId, List<StaffingScenarioAllocation> scoped,
                               Map<Long, Engineer> engineers, Map<Long, ProjectPosition> positions,
                               YearMonth month) {
        int workingDays = workingDays(month.atDay(1), month.atEndOfMonth());
        MonthAgg agg = new MonthAgg();
        Set<Long> engineerIds = new LinkedHashSet<>();
        for (StaffingScenarioAllocation allocation : scoped) {
            Set<LocalDate> dates = parseDates(allocation.getDates());
            int inDays = 0;
            for (LocalDate day : dates) {
                if (YearMonth.from(day).equals(month)) {
                    inDays++;
                }
            }
            if (inDays <= 0) {
                continue;
            }
            engineerIds.add(allocation.getEngineerId());
            BigDecimal fte = percentOf(allocation.getPercent())
                    .multiply(BigDecimal.valueOf(inDays))
                    .divide(BigDecimal.valueOf(Math.max(1, workingDays)), 4, RoundingMode.HALF_UP)
                    .setScale(2, RoundingMode.HALF_UP);
            agg.supplyFte = agg.supplyFte.add(fte);
            if (allocation.getPositionId() != null) {
                ProjectPosition position = positions.get(allocation.getPositionId());
                Engineer engineer = engineers.get(allocation.getEngineerId());
                if (position != null && engineer != null) {
                    BigDecimal unitPrice = position.getUnitPriceMin() != null
                            ? position.getUnitPriceMin()
                            : (position.getUnitPriceMax() != null ? position.getUnitPriceMax() : BigDecimal.ZERO);
                    BigDecimal cost = engineer.getExpectedUnitPrice() == null
                            ? BigDecimal.ZERO : engineer.getExpectedUnitPrice();
                    agg.grossProfit = agg.grossProfit.add(unitPrice.subtract(cost).multiply(fte)
                            .setScale(2, RoundingMode.HALF_UP));
                }
            }
        }
        agg.engineerCount = engineerIds.size();
        return agg;
    }

    /** 閲覧者のscope（DataScope/組織scope）。null=全件。 */
    private Set<Long> viewerAllowedEngineers() {
        return dataScopeService.isScoped() ? dataScopeService.allowedEngineerIds() : null;
    }

    private void requireVisible(StaffingScenario scenario) {
        Long userId = SecurityUtils.currentUserId();
        if (userId != null && userId.equals(scenario.getOwnerUserId())) {
            return;
        }
        if (Integer.valueOf(1).equals(scenario.getSharedFlag())) {
            return;
        }
        throw BusinessException.of(403, "error.staffing.scenarioForbidden");
    }

    private Map<Long, Engineer> loadEngineers(List<StaffingScenarioAllocation> allocations) {
        Set<Long> ids = allocations.stream()
                .map(StaffingScenarioAllocation::getEngineerId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return engineerMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Engineer::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, ProjectPosition> loadPositions(List<StaffingScenarioAllocation> allocations) {
        Set<Long> ids = allocations.stream()
                .map(StaffingScenarioAllocation::getPositionId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return positionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ProjectPosition::getId, Function.identity(), (a, b) -> a));
    }

    private Set<LocalDate> parseDates(String datesJson) {
        try {
            List<String> raw = objectMapper.readValue(datesJson, new TypeReference<List<String>>() {
            });
            return raw.stream().map(LocalDate::parse).collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            return Set.of();
        }
    }

    private LocalDate firstDateOf(String datesJson) {
        return parseDates(datesJson).stream().min(Comparator.naturalOrder()).orElse(null);
    }

    private LocalDate lastDateOf(String datesJson) {
        return parseDates(datesJson).stream().max(Comparator.naturalOrder()).orElse(null);
    }

    private BigDecimal percentOf(BigDecimal percent) {
        return (percent == null ? BigDecimal.valueOf(100) : percent)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private int workingDays(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    private static class MonthAgg {
        int engineerCount;
        BigDecimal supplyFte = BigDecimal.ZERO;
        BigDecimal grossProfit = BigDecimal.ZERO;
    }
}
