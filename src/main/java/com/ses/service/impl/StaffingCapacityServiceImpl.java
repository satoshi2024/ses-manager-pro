package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.constant.StatusConstants;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.LeaveRequest;
import com.ses.entity.WorkCalendar;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.LeaveRequestMapper;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import com.ses.service.UtilizationCalcService;
import com.ses.service.staffing.StaffingCapacityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;

/**
 * 需給集計の実装（T076 F2）。
 *
 * <p>FTE換算: 月内の稼働可能日数（m_work_calendarのday_type='通常'・承認済休暇控除後、無い場合は法人既定=平日）
 * に対する在籍日数比 × 配賦率。cap=配賦率（休暇で契約FTEを自動変更しない）。
 * plan/actualの排他はSQLのWHERE句（source_contract_idのNULL/NOT NULL）で行う（design §5.4）。
 */
@Service
@RequiredArgsConstructor
public class StaffingCapacityServiceImpl implements StaffingCapacityService {

    /** 勤務日（m_work_calendar_day.day_type）。稼働可能日数のカウント対象。 */
    private static final String DAY_TYPE_WORKING = "通常";

    /** 休暇種別→日割り区分（LeaveMinutesCalculatorと同一の振り分け）。 */
    private static final Set<String> HALF_DAY_TYPES = Set.of("半休");
    private static final Set<String> HOURLY_TYPES = Set.of("時間休");

    private static final int FULL_DAY_MINUTES = 480;

    private final WorkCalendarMapper workCalendarMapper;
    private final WorkCalendarDayMapper workCalendarDayMapper;
    private final LeaveRequestMapper leaveRequestMapper;
    private final AllocationPlanMapper allocationMapper;
    private final ContractMapper contractMapper;
    private final UtilizationCalcService utilizationCalcService;

    @Override
    @Transactional(readOnly = true)
    public EngineerMonthSupply supply(Engineer engineer, YearMonth month, LocalDate asOf) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate windowEnd = monthEnd;

        Long calendarId = resolveCalendarId(engineer, monthStart, monthEnd);
        boolean hasCalendarDays = calendarId != null && hasDayRows(calendarId, monthStart, monthEnd);
        int workingDays = hasCalendarDays
                ? countWorkingDays(calendarId, monthStart, monthEnd)
                : countWeekdays(monthStart, monthEnd);
        int leaveDays = countLeaveDays(engineer.getId(), monthStart, monthEnd);
        int availableDays = Math.max(0, workingDays - leaveDays);

        // 退場予定: 最終契約終了日以降は供給0・稼働可能日数0
        LocalDate retirementLimit = null;
        if (StatusConstants.ENGINEER_LEAVING.equals(engineer.getStatus())) {
            retirementLimit = lastActiveContractEnd(engineer.getId());
            if (retirementLimit != null && monthStart.isAfter(retirementLimit)) {
                return new EngineerMonthSupply(engineer.getId(), month, workingDays, leaveDays, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO);
            }
            if (retirementLimit != null && windowEnd.isAfter(retirementLimit)) {
                windowEnd = retirementLimit;
            }
        }

        BigDecimal actualFte = sumActualFte(engineer.getId(), calendarId, monthStart, monthEnd, windowEnd,
                availableDays, asOf);
        BigDecimal planFte = sumPlanFte(engineer.getId(), calendarId, monthStart, monthEnd, windowEnd,
                availableDays);
        return new EngineerMonthSupply(engineer.getId(), month, workingDays, leaveDays, availableDays,
                actualFte, planFte);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EngineerMonthSupply> supplyBatch(List<Engineer> engineers, YearMonth from, YearMonth to, LocalDate asOf) {
        List<EngineerMonthSupply> result = new ArrayList<>();
        for (Engineer engineer : engineers) {
            for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
                result.add(supply(engineer, month, asOf));
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public UtilizationCalcService.UtilizationSnapshot utilization(YearMonth month, List<Engineer> engineers,
                                                                  Map<Long, List<Contract>> contractsByEngineer,
                                                                  boolean assumeRenew) {
        // design §5.1: 稼働率の口径はUtilizationCalcServiceを唯一の正とする（dashboard KPIと同一値）
        return utilizationCalcService.calc(month, engineers, contractsByEngineer, assumeRenew);
    }

    // ---------------------------------------------------------------
    // actual（契約由来）FTE
    // ---------------------------------------------------------------

    private BigDecimal sumActualFte(Long engineerId, Long calendarId, LocalDate monthStart, LocalDate monthEnd,
                                    LocalDate windowEnd, int availableDays, LocalDate asOf) {
        // WHERE句でactual（source_contract_id IS NOT NULL）だけを選択（design §5.4の排他）。
        // 終了日条件はSQLに置かない: 更新継続（renewal）で実効終了日が延長される行を
        // SQL側で除外すると再発行の判定前に消えるため、期間の重なりはJava側で判定する
        // （対象は要員1人分の行のみで、全engineer×全dayの直積にはならない）。
        List<AllocationPlan> actuals = allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getEngineerId, engineerId)
                .isNotNull(AllocationPlan::getSourceContractId)
                .eq(AllocationPlan::getStatus, STATUS_CONFIRMED)
                .le(AllocationPlan::getStartDate, monthEnd));
        if (actuals.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<Long> contractIds = actuals.stream()
                .map(AllocationPlan::getSourceContractId).collect(Collectors.toSet());
        Map<Long, Contract> contracts = contractMapper.selectBatchIds(contractIds).stream()
                .collect(Collectors.toMap(Contract::getId, c -> c, (a, b) -> a));
        boolean assumeRenew = UtilizationCalcService.resolveAssumeRenew(systemConfigService);

        BigDecimal sum = BigDecimal.ZERO;
        for (AllocationPlan row : actuals) {
            Contract contract = contracts.get(row.getSourceContractId());
            if (contract == null || !isContractEffective(contract)) {
                continue;
            }
            LocalDate rowStart = row.getStartDate() == null ? monthStart : row.getStartDate();
            LocalDate effectiveEnd = row.getEndDate();
            if (effectiveEnd != null && shouldRenew(contract, assumeRenew)) {
                effectiveEnd = null; // 更新継続: 終了日なし扱い
            }
            LocalDate from = maxDate(rowStart, monthStart);
            LocalDate to = minDate(effectiveEnd, windowEnd);
            if (from.isAfter(to)) {
                continue;
            }
            int inDays = countWorkingDays(calendarId, from, to);
            BigDecimal fte = fteOf(inDays, availableDays, BigDecimal.valueOf(100));
            sum = sum.add(fte);
        }
        return sum;
    }

    /** 契約が供給に寄与する状態か（準備中/稼動中。終了/解約は同期でactual破棄済みの防御条件）。 */
    private boolean isContractEffective(Contract contract) {
        return StatusConstants.CONTRACT_PREPARING.equals(contract.getStatus())
                || StatusConstants.CONTRACT_ACTIVE.equals(contract.getStatus());
    }

    /** 更新済契約（UtilizationCalcService.isActiveInMonthと同一規則）。 */
    private boolean shouldRenew(Contract contract, boolean assumeRenew) {
        if (!assumeRenew || contract.getAutoRenew() == null || contract.getAutoRenew() != 1) {
            return false;
        }
        return !com.ses.common.constant.RenewalState.DECISION_END.equals(contract.getRenewalDecision());
    }

    // ---------------------------------------------------------------
    // plan（source_contract_id IS NULL）FTE
    // ---------------------------------------------------------------

    private BigDecimal sumPlanFte(Long engineerId, Long calendarId, LocalDate monthStart, LocalDate monthEnd,
                                  LocalDate windowEnd, int availableDays) {
        // WHERE句でplan（source_contract_id IS NULL）だけを選択（design §5.4の排他）
        List<AllocationPlan> plans = allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getEngineerId, engineerId)
                .isNull(AllocationPlan::getSourceContractId)
                .eq(AllocationPlan::getStatus, STATUS_CONFIRMED)
                .le(AllocationPlan::getStartDate, monthEnd)
                .and(w -> w.isNull(AllocationPlan::getEndDate)
                        .or().ge(AllocationPlan::getEndDate, monthStart)));
        BigDecimal sum = BigDecimal.ZERO;
        for (AllocationPlan plan : plans) {
            LocalDate from = maxDate(plan.getStartDate(), monthStart);
            LocalDate to = minDate(plan.getEndDate() == null ? windowEnd : plan.getEndDate(), windowEnd, monthEnd);
            if (from.isAfter(to)) {
                continue;
            }
            int inDays = countWorkingDays(calendarId, from, to);
            BigDecimal percent = plan.getAllocationPercent() == null
                    ? BigDecimal.valueOf(100) : plan.getAllocationPercent();
            sum = sum.add(fteOf(inDays, availableDays, percent));
        }
        return sum;
    }

    /** 月別FTE = 在籍日数比 × 配賦率。cap=配賦率（休暇等でFTEを自動変更しない。design §5.2）。 */
    private BigDecimal fteOf(int inDays, int availableDays, BigDecimal percent) {
        if (availableDays <= 0 || inDays <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = BigDecimal.valueOf(inDays)
                .divide(BigDecimal.valueOf(availableDays), 4, java.math.RoundingMode.HALF_UP);
        BigDecimal fte = ratio.multiply(percent).setScale(2, java.math.RoundingMode.HALF_UP);
        return fte.min(percent);
    }

    // ---------------------------------------------------------------
    // 稼働可能日数（m_work_calendar基準・法人既定=平日）
    // ---------------------------------------------------------------

    private Long resolveCalendarId(Engineer engineer, LocalDate monthStart, LocalDate monthEnd) {
        List<WorkCalendar> calendars = workCalendarMapper.selectList(new LambdaQueryWrapper<WorkCalendar>()
                .eq(WorkCalendar::getEngineerId, engineer.getId())
                .eq(WorkCalendar::getStatus, "有効")
                .le(WorkCalendar::getValidFrom, monthEnd)
                .and(w -> w.isNull(WorkCalendar::getValidTo)
                        .or().ge(WorkCalendar::getValidTo, monthStart))
                .orderByDesc(WorkCalendar::getId)
                .last("LIMIT 1"));
        return calendars.isEmpty() ? null : calendars.get(0).getId();
    }

    private boolean hasDayRows(Long calendarId, LocalDate monthStart, LocalDate monthEnd) {
        return workCalendarDayMapper.selectCount(new LambdaQueryWrapper<WorkCalendarDay>()
                .eq(WorkCalendarDay::getCalendarId, calendarId)
                .between(WorkCalendarDay::getCalendarDate, monthStart, monthEnd)) > 0;
    }

    private int countWorkingDays(Long calendarId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return 0;
        }
        if (calendarId == null) {
            return countWeekdays(from, to);
        }
        Long count = workCalendarDayMapper.selectCount(new LambdaQueryWrapper<WorkCalendarDay>()
                .eq(WorkCalendarDay::getCalendarId, calendarId)
                .eq(WorkCalendarDay::getDayType, DAY_TYPE_WORKING)
                .between(WorkCalendarDay::getCalendarDate, from, to));
        // カレンダーはあるが対象期間の日行が無い場合は法人既定（平日）へフォールバック
        return count > 0 ? count.intValue() : countWeekdays(from, to);
    }

    private int countWeekdays(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------
    // 休暇（承認済み休暇が稼働可能日数を減らす。design §2）
    // ---------------------------------------------------------------

    private int countLeaveDays(Long engineerId, LocalDate monthStart, LocalDate monthEnd) {
        List<LeaveRequest> leaves = leaveRequestMapper.selectList(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getEngineerId, engineerId)
                .eq(LeaveRequest::getStatus, "承認済")
                .le(LeaveRequest::getStartDate, monthEnd)
                .and(w -> w.isNull(LeaveRequest::getEndDate)
                        .or().ge(LeaveRequest::getEndDate, monthStart)));
        double days = 0;
        for (LeaveRequest leave : leaves) {
            days += leaveDayFraction(leave, monthStart, monthEnd);
        }
        return (int) Math.round(days);
    }

    /** 休暇の日割り: 全日=1/日、半休=0.5/日、時間休=min(1, 時間/480)を対象月にクリップして合算。 */
    private double leaveDayFraction(LeaveRequest leave, LocalDate monthStart, LocalDate monthEnd) {
        if (HOURLY_TYPES.contains(leave.getLeaveType())) {
            LocalDate date = leave.getStartDate();
            if (date == null || date.isBefore(monthStart) || date.isAfter(monthEnd)) {
                return 0;
            }
            if (leave.getStartTime() == null || leave.getEndTime() == null) {
                return 1;
            }
            int minutes = (int) java.time.Duration.between(leave.getStartTime(), leave.getEndTime()).toMinutes();
            return Math.min(1.0, Math.max(0, minutes) / (double) FULL_DAY_MINUTES);
        }
        double perDay = HALF_DAY_TYPES.contains(leave.getLeaveType()) ? 0.5 : 1.0;
        LocalDate from = maxDate(leave.getStartDate(), monthStart);
        LocalDate to = minDate(leave.getEndDate() == null ? monthEnd : leave.getEndDate(), monthEnd);
        if (from.isAfter(to)) {
            return 0;
        }
        return (to.toEpochDay() - from.toEpochDay() + 1) * perDay;
    }

    // ---------------------------------------------------------------
    // 退場予定（退職の反映）
    // ---------------------------------------------------------------

    /** 退場予定要員の最終契約終了日（準備中/稼動中のend_date最大値。無ければnull=制限なし）。 */
    private LocalDate lastActiveContractEnd(Long engineerId) {
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getEngineerId, engineerId)
                .in(Contract::getStatus, List.of(StatusConstants.CONTRACT_PREPARING, StatusConstants.CONTRACT_ACTIVE))
                .isNotNull(Contract::getEndDate));
        return contracts.stream()
                .map(Contract::getEndDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    // ---------------------------------------------------------------

    private LocalDate maxDate(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : (a.isAfter(b) ? a : b);
    }

    private LocalDate minDate(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : (a.isBefore(b) ? a : b);
    }

    private LocalDate minDate(LocalDate a, LocalDate b, LocalDate c) {
        return minDate(minDate(a, b), c);
    }

    private final com.ses.service.SystemConfigService systemConfigService;
}
