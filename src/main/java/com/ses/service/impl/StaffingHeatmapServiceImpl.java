package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.dto.staffing.HeatmapDto;
import com.ses.dto.staffing.ShortfallDrilldownDto;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.ProjectPosition;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.security.DataScopeService;
import com.ses.service.staffing.StaffingCapacityService;
import com.ses.service.staffing.StaffingClock;
import com.ses.service.staffing.StaffingHeatmapService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.ses.entity.AllocationPlan.STATUS_DISCARDED;
import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;

/**
 * 需給heatmap集計の実装（T078 B1）。
 *
 * <p>集計はengineer×月のループで行い、全engineer×全dayの直積をJava memoryへ作らない（design §4）。
 * 需要/供給のFTEはdesign §5.2の月別FTE口径（月内稼働日数=法人既定の平日で按分）に揃える。
 * グループ帰属は分割性を保つため、供給はengineer1人を1グループへ、需要はposition1件を1グループへ
 * 割り当てる（全社合計＝内訳合計）。
 */
@Service
@RequiredArgsConstructor
public class StaffingHeatmapServiceImpl implements StaffingHeatmapService {

    private static final String ROLE_HR = "HR";
    private static final String GROUP_UNASSIGNED = "—";

    private final EngineerMapper engineerMapper;
    private final EngineerSkillMapper engineerSkillMapper;
    private final ProjectPositionMapper positionMapper;
    private final ProjectMapper projectMapper;
    private final AllocationPlanMapper allocationMapper;
    private final StaffingCapacityService capacityService;
    private final DataScopeService dataScopeService;
    private final StaffingClock clock;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public HeatmapDto heatmap(LocalDate asOf) {
        YearMonth from = YearMonth.from(asOf);
        YearMonth to = from.plusMonths(StaffingClock.HORIZON_MONTHS - 1);
        return heatmap(asOf, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public HeatmapDto heatmap(LocalDate asOf, YearMonth from, YearMonth to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw BusinessException.of(400, "error.staffing.invalidPeriod");
        }
        if (to.isAfter(YearMonth.from(clock.horizonEnd()))
                || from.until(to, java.time.temporal.ChronoUnit.MONTHS) >= StaffingClock.HORIZON_MONTHS) {
            throw BusinessException.of(400, "error.staffing.horizonExceeded");
        }

        boolean maskCost = ROLE_HR.equals(SecurityUtils.currentRole());
        List<Engineer> engineers = loadEngineers();
        Map<Long, String> primarySkillByEngineer = primarySkills(engineers);
        List<ProjectPosition> positions = loadPositions();
        List<AllocationPlan> allocations = loadAllocations();

        // グループ順序を決定的にする（TreeMap）。
        Map<String, Map<YearMonth, MonthAccum>> skillAcc = new TreeMap<>();
        Map<String, Map<YearMonth, MonthAccum>> roleAcc = new TreeMap<>();
        Map<String, Map<YearMonth, MonthAccum>> locationAcc = new TreeMap<>();

        List<YearMonth> months = monthsBetween(from, to);
        Map<Long, Map<YearMonth, StaffingCapacityService.EngineerMonthSupply>> supplyByEngineer =
                supplyIndex(engineers, from, to, asOf);

        // ---- 需要（position） ----
        for (ProjectPosition position : positions) {
            String skillGroup = firstSkillOf(position.getSkillsJson());
            for (YearMonth month : months) {
                if (!activeInMonth(position, month)) {
                    continue;
                }
                int inDays = overlapWorkingDays(position.getStartDate(),
                        position.getEndDate() == null ? clock.horizonEnd() : position.getEndDate(), month);
                int monthDays = workingDays(month);
                if (inDays <= 0 || monthDays <= 0) {
                    continue;
                }
                BigDecimal ratio = BigDecimal.valueOf(inDays)
                        .divide(BigDecimal.valueOf(monthDays), 4, RoundingMode.HALF_UP);
                BigDecimal demand = ratio.multiply(BigDecimal.valueOf(value(position.getRequiredCount())))
                        .multiply(percentOf(position.getAllocationPercent()))
                        .setScale(2, RoundingMode.HALF_UP);
                acc(skillAcc, skillGroup, month).demand = add(acc(skillAcc, skillGroup, month).demand, demand);
                acc(roleAcc, valueOr(position.getRoleName()), month).demand =
                        add(acc(roleAcc, valueOr(position.getRoleName()), month).demand, demand);
                acc(locationAcc, valueOr(position.getLocation()), month).demand =
                        add(acc(locationAcc, valueOr(position.getLocation()), month).demand, demand);
            }
        }

        // ---- 供給（engineer）＋ bench ----
        for (Engineer engineer : engineers) {
            String primarySkill = primarySkillByEngineer.getOrDefault(engineer.getId(), GROUP_UNASSIGNED);
            for (YearMonth month : months) {
                StaffingCapacityService.EngineerMonthSupply supply =
                        supplyByEngineer.get(engineer.getId()).get(month);
                BigDecimal supplyFte = supply.totalFte().setScale(2, RoundingMode.HALF_UP);
                AllocationPlan attributed = attributionAllocation(engineer.getId(), allocations, month);
                if (attributed == null || supplyFte.signum() <= 0) {
                    // bench（供給なし・または供給なし扱い）: 未割当/primarySkillグループへ
                    BigDecimal benchFte = BigDecimal.ONE.subtract(supplyFte).max(BigDecimal.ZERO)
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal benchCost = benchFte.multiply(valueOrZero(engineer.getExpectedUnitPrice()))
                            .setScale(2, RoundingMode.HALF_UP);
                    addBench(skillAcc, primarySkill, month, supplyFte, benchCost);
                    addBench(roleAcc, GROUP_UNASSIGNED, month, supplyFte, benchCost);
                    addBench(locationAcc, GROUP_UNASSIGNED, month, supplyFte, benchCost);
                    continue;
                }
                ProjectPosition position = positionOf(attributed.getPositionId(), positions);
                String roleGroup = position == null ? GROUP_UNASSIGNED : valueOr(position.getRoleName());
                String locationGroup = position == null ? GROUP_UNASSIGNED : valueOr(position.getLocation());
                acc(skillAcc, primarySkill, month).supply = add(acc(skillAcc, primarySkill, month).supply, supplyFte);
                acc(roleAcc, roleGroup, month).supply = add(acc(roleAcc, roleGroup, month).supply, supplyFte);
                acc(locationAcc, locationGroup, month).supply = add(acc(locationAcc, locationGroup, month).supply, supplyFte);
            }
        }

        return build(asOf, months, maskCost, skillAcc, roleAcc, locationAcc);
    }

    @Override
    @Transactional(readOnly = true)
    public ShortfallDrilldownDto drilldown(YearMonth month, String dimension, String group, LocalDate asOf) {
        if (dimension == null || group == null || month == null) {
            throw BusinessException.of(400, "error.staffing.invalidPeriod");
        }
        boolean maskCost = ROLE_HR.equals(SecurityUtils.currentRole());
        List<ProjectPosition> positions = loadPositions();
        List<AllocationPlan> allocations = loadAllocations();
        List<Engineer> engineers = loadEngineers();
        Map<Long, String> primarySkillByEngineer = primarySkills(engineers);
        Map<Long, Map<YearMonth, StaffingCapacityService.EngineerMonthSupply>> supplyByEngineer =
                supplyIndex(engineers, month, month, asOf);
        Set<Long> projectIds = positions.stream().map(ProjectPosition::getProjectId).collect(Collectors.toSet());
        Map<Long, Project> projects = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity(), (a, b) -> a));

        // 需要側: 指定グループに属するposition（対象月に有効）
        List<ShortfallDrilldownDto.PositionLine> positionLines = new ArrayList<>();
        for (ProjectPosition position : positions) {
            if (!matchesGroup(position, dimension, group) || !activeInMonth(position, month)) {
                continue;
            }
            ShortfallDrilldownDto.PositionLine line = new ShortfallDrilldownDto.PositionLine();
            line.setPositionId(position.getId());
            line.setPositionNo(position.getPositionNo());
            line.setRoleName(position.getRoleName());
            line.setProjectId(position.getProjectId());
            Project project = projects.get(position.getProjectId());
            line.setProjectName(project == null ? null : project.getProjectName());
            line.setRequiredCount(position.getRequiredCount());
            line.setStatus(position.getStatus());
            if (!maskCost) {
                line.setUnitPriceMin(position.getUnitPriceMin());
                line.setUnitPriceMax(position.getUnitPriceMax());
            }
            positionLines.add(line);
        }

        // 供給側: 指定グループに帰属するengineer（対象月に供給>0）
        List<ShortfallDrilldownDto.EngineerLine> engineerLines = new ArrayList<>();
        for (Engineer engineer : engineers) {
            StaffingCapacityService.EngineerMonthSupply supply =
                    supplyByEngineer.get(engineer.getId()).get(month);
            if (supply.totalFte().signum() <= 0) {
                continue;
            }
            AllocationPlan attributed = attributionAllocation(engineer.getId(), allocations, month);
            ProjectPosition position = attributed == null ? null : positionOf(attributed.getPositionId(), positions);
            boolean matches = matchesEngineerGroup(engineer, position, primarySkillByEngineer.get(engineer.getId()),
                    dimension, group);
            if (!matches) {
                continue;
            }
            ShortfallDrilldownDto.EngineerLine line = new ShortfallDrilldownDto.EngineerLine();
            line.setEngineerId(engineer.getId());
            line.setEngineerName(engineer.getFullName());
            line.setPrimarySkill(primarySkillByEngineer.get(engineer.getId()));
            line.setSupplyFte(supply.totalFte().setScale(2, RoundingMode.HALF_UP));
            if (!maskCost) {
                line.setUnitPrice(engineer.getExpectedUnitPrice());
            }
            engineerLines.add(line);
        }
        return new ShortfallDrilldownDto(String.valueOf(month), dimension, group, positionLines, engineerLines);
    }

    private Map<Long, Map<YearMonth, StaffingCapacityService.EngineerMonthSupply>> supplyIndex(
            List<Engineer> engineers, YearMonth from, YearMonth to, LocalDate asOf) {
        return capacityService.supplyBatch(engineers, from, to, asOf).stream()
                .collect(Collectors.groupingBy(
                        StaffingCapacityService.EngineerMonthSupply::engineerId,
                        LinkedHashMap::new,
                        Collectors.toMap(
                                StaffingCapacityService.EngineerMonthSupply::month,
                                Function.identity(),
                                (a, b) -> a,
                                LinkedHashMap::new)));
    }

    // ---------------------------------------------------------------
    // 集計ヘルパー
    // ---------------------------------------------------------------

    private HeatmapDto build(LocalDate asOf, List<YearMonth> months, boolean maskCost,
                             Map<String, Map<YearMonth, MonthAccum>> skillAcc,
                             Map<String, Map<YearMonth, MonthAccum>> roleAcc,
                             Map<String, Map<YearMonth, MonthAccum>> locationAcc) {
        // 全社合計は1つの次元（role）の内訳合計から組む。各次元は分割性を持つため、
        // どの次元のΣでも全社合計と一致する（全社=内訳合計の不変条件）。
        Map<YearMonth, MonthAccum> totals = new LinkedHashMap<>();
        for (Map.Entry<String, Map<YearMonth, MonthAccum>> entry : roleAcc.entrySet()) {
            for (Map.Entry<YearMonth, MonthAccum> cell : entry.getValue().entrySet()) {
                MonthAccum total = totals.computeIfAbsent(cell.getKey(), k -> new MonthAccum());
                total.demand = add(total.demand, cell.getValue().demand);
                total.supply = add(total.supply, cell.getValue().supply);
                total.benchCost = add(total.benchCost, cell.getValue().benchCost);
            }
        }
        List<HeatmapDto.DimensionRow> skillRows = toRows(skillAcc, months, maskCost);
        List<HeatmapDto.DimensionRow> roleRows = toRows(roleAcc, months, maskCost);
        List<HeatmapDto.DimensionRow> locationRows = toRows(locationAcc, months, maskCost);
        List<HeatmapDto.MonthCell> totalCells = new ArrayList<>();
        for (YearMonth month : months) {
            MonthAccum acc = totals.getOrDefault(month, new MonthAccum());
            totalCells.add(new HeatmapDto.MonthCell(String.valueOf(month),
                    acc.demand, acc.supply,
                    shortfallOf(acc), surplusOf(acc), maskCost ? null : acc.benchCost));
        }
        return new HeatmapDto(asOf, skillRows, roleRows, locationRows, totalCells);
    }

    private List<HeatmapDto.DimensionRow> toRows(Map<String, Map<YearMonth, MonthAccum>> acc,
                                                 List<YearMonth> months, boolean maskCost) {
        List<HeatmapDto.DimensionRow> rows = new ArrayList<>();
        for (Map.Entry<String, Map<YearMonth, MonthAccum>> entry : acc.entrySet()) {
            List<HeatmapDto.MonthCell> cells = new ArrayList<>();
            for (YearMonth month : months) {
                MonthAccum cell = entry.getValue().getOrDefault(month, new MonthAccum());
                cells.add(new HeatmapDto.MonthCell(String.valueOf(month),
                        cell.demand, cell.supply, shortfallOf(cell), surplusOf(cell),
                        maskCost ? null : cell.benchCost));
            }
            rows.add(new HeatmapDto.DimensionRow(entry.getKey(), cells));
        }
        return rows;
    }

    private static BigDecimal shortfallOf(MonthAccum cell) {
        return cell.demand.subtract(cell.supply).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal surplusOf(MonthAccum cell) {
        return cell.supply.subtract(cell.demand).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private void addBench(Map<String, Map<YearMonth, MonthAccum>> acc, String group, YearMonth month,
                          BigDecimal supplyFte, BigDecimal benchCost) {
        MonthAccum cell = acc(acc, group, month);
        cell.supply = add(cell.supply, supplyFte);
        cell.benchCost = add(cell.benchCost, benchCost);
    }

    private MonthAccum acc(Map<String, Map<YearMonth, MonthAccum>> acc, String group, YearMonth month) {
        return acc.computeIfAbsent(group, k -> new TreeMap<>())
                .computeIfAbsent(month, k -> new MonthAccum());
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a == null ? b : a.add(b);
    }

    /** 供給の帰属先（対象月に有効な確定配置のうち開始日が最も早いもの）。分割性を保つ。 */
    private AllocationPlan attributionAllocation(Long engineerId, List<AllocationPlan> allocations, YearMonth month) {
        return allocations.stream()
                .filter(a -> engineerId.equals(a.getEngineerId()))
                .filter(a -> STATUS_CONFIRMED.equals(a.getStatus()))
                .filter(a -> a.getPositionId() != null)
                .filter(a -> overlaps(a, month))
                .min(Comparator.comparing(AllocationPlan::getStartDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private boolean overlaps(AllocationPlan allocation, YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        if (allocation.getStartDate() != null && allocation.getStartDate().isAfter(monthEnd)) {
            return false;
        }
        return allocation.getEndDate() == null || !allocation.getEndDate().isBefore(monthStart);
    }

    private boolean activeInMonth(ProjectPosition position, YearMonth month) {
        // 取消済みpositionは需要・不足に計上しない（S12-R1-P2-04）
        if (ProjectPosition.STATUS_CANCELLED.equals(position.getStatus())) {
            return false;
        }
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        if (position.getStartDate() != null && position.getStartDate().isAfter(monthEnd)) {
            return false;
        }
        return position.getEndDate() == null || !position.getEndDate().isBefore(monthStart);
    }

    private ProjectPosition positionOf(Long positionId, List<ProjectPosition> positions) {
        if (positionId == null) {
            return null;
        }
        return positions.stream().filter(p -> positionId.equals(p.getId())).findFirst().orElse(null);
    }

    private boolean matchesGroup(ProjectPosition position, String dimension, String group) {
        return switch (dimension) {
            case "role" -> group.equals(valueOr(position.getRoleName()));
            case "location" -> group.equals(valueOr(position.getLocation()));
            case "skill" -> group.equals(firstSkillOf(position.getSkillsJson()));
            default -> false;
        };
    }

    private boolean matchesEngineerGroup(Engineer engineer, ProjectPosition position, String primarySkill,
                                         String dimension, String group) {
        return switch (dimension) {
            case "skill" -> group.equals(primarySkill);
            case "role" -> position != null && group.equals(valueOr(position.getRoleName()));
            case "location" -> position != null && group.equals(valueOr(position.getLocation()));
            default -> false;
        };
    }

    // ---------------------------------------------------------------
    // データロード（SQL境界でscope適用）
    // ---------------------------------------------------------------

    private List<Engineer> loadEngineers() {
        Set<Long> allowed = dataScopeService.isScoped() ? dataScopeService.allowedEngineerIds() : null;
        LambdaQueryWrapper<Engineer> query = new LambdaQueryWrapper<>();
        if (allowed != null) {
            if (allowed.isEmpty()) {
                query.apply("1 = 0");
            } else {
                query.in(Engineer::getId, allowed);
            }
        }
        query.orderByAsc(Engineer::getId);
        return engineerMapper.selectList(query);
    }

    private List<ProjectPosition> loadPositions() {
        Set<Long> allowedProjects = dataScopeService.isScoped() ? dataScopeService.allowedProjectIds() : null;
        if (allowedProjects == null) {
            return positionMapper.selectList(new LambdaQueryWrapper<ProjectPosition>()
                    .orderByAsc(ProjectPosition::getId));
        }
        if (allowedProjects.isEmpty()) {
            return List.of();
        }
        return positionMapper.selectList(new LambdaQueryWrapper<ProjectPosition>()
                .in(ProjectPosition::getProjectId, allowedProjects)
                .orderByAsc(ProjectPosition::getId));
    }

    private List<AllocationPlan> loadAllocations() {
        List<Engineer> engineers = loadEngineers();
        if (engineers.isEmpty()) {
            return List.of();
        }
        Set<Long> engineerIds = engineers.stream().map(Engineer::getId).collect(Collectors.toSet());
        return allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .in(AllocationPlan::getEngineerId, engineerIds)
                .ne(AllocationPlan::getStatus, STATUS_DISCARDED)
                .orderByAsc(AllocationPlan::getId));
    }

    private Map<Long, String> primarySkills(List<Engineer> engineers) {
        if (engineers.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = engineers.stream().map(Engineer::getId).toList();
        List<EngineerSkillDetailDto> candidates = engineerSkillMapper.selectTopSkillCandidates(ids);
        Map<Long, String> result = new LinkedHashMap<>();
        for (EngineerSkillDetailDto dto : candidates) {
            result.putIfAbsent(dto.getEngineerId(), dto.getSkillName());
        }
        return result;
    }

    // ---------------------------------------------------------------
    // 日数・グループヘルパー
    // ---------------------------------------------------------------

    private List<YearMonth> monthsBetween(YearMonth from, YearMonth to) {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            months.add(month);
        }
        return months;
    }

    /** 対象期間∩対象月の平日数（法人既定。稼働日数のfallbackと同一）。 */
    private int overlapWorkingDays(LocalDate start, LocalDate end, YearMonth month) {
        LocalDate from = start == null ? month.atDay(1) : max(start, month.atDay(1));
        LocalDate to = end == null ? month.atEndOfMonth() : min(end, month.atEndOfMonth());
        if (from.isAfter(to)) {
            return 0;
        }
        return workingDays(from, to);
    }

    private int workingDays(YearMonth month) {
        return workingDays(month.atDay(1), month.atEndOfMonth());
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

    /** positionのskills_json（JSON配列）の先頭skill名。分割性のため1ポジション=1グループ。 */
    private String firstSkillOf(String skillsJson) {
        if (skillsJson == null || skillsJson.isBlank()) {
            return GROUP_UNASSIGNED;
        }
        try {
            List<String> skills = objectMapper
                    .readValue(skillsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
            return skills == null || skills.isEmpty() ? GROUP_UNASSIGNED : skills.get(0);
        } catch (Exception e) {
            return GROUP_UNASSIGNED;
        }
    }

    private BigDecimal percentOf(BigDecimal percent) {
        return (percent == null ? BigDecimal.valueOf(100) : percent)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private String valueOr(String value) {
        return value == null || value.isBlank() ? GROUP_UNASSIGNED : value;
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static class MonthAccum {
        BigDecimal demand = BigDecimal.ZERO;
        BigDecimal supply = BigDecimal.ZERO;
        BigDecimal benchCost = BigDecimal.ZERO;
    }
}
