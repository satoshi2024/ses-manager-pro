package com.ses.staffing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Engineer;
import com.ses.entity.LeaveRequest;
import com.ses.entity.WorkCalendar;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.LeaveRequestMapper;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.UtilizationCalcService;
import com.ses.service.impl.StaffingCapacityServiceImpl;
import com.ses.service.staffing.StaffingCapacityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** supplyBatchが要員×月ごとのN+1 queryへ退行しないことを検証する。 */
@ExtendWith(MockitoExtension.class)
class StaffingCapacityBatchQueryTest {

    @Mock private WorkCalendarMapper workCalendarMapper;
    @Mock private WorkCalendarDayMapper workCalendarDayMapper;
    @Mock private LeaveRequestMapper leaveRequestMapper;
    @Mock private AllocationPlanMapper allocationMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private UtilizationCalcService utilizationCalcService;
    @Mock private SystemConfigService systemConfigService;

    @InjectMocks
    private StaffingCapacityServiceImpl service;

    @Test
    void 二百要員二十四か月でも入力テーブルごとのqueryは一回だけ() {
        when(workCalendarMapper.selectList(any(Wrapper.class))).thenReturn(List.<WorkCalendar>of());
        when(leaveRequestMapper.selectList(any(Wrapper.class))).thenReturn(List.<LeaveRequest>of());
        when(allocationMapper.selectList(any(Wrapper.class))).thenReturn(List.<AllocationPlan>of());
        when(contractMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<Engineer> engineers = LongStream.rangeClosed(1, 200)
                .mapToObj(id -> {
                    Engineer engineer = new Engineer();
                    engineer.setId(id);
                    engineer.setStatus("Bench");
                    return engineer;
                })
                .toList();

        List<StaffingCapacityService.EngineerMonthSupply> rows = service.supplyBatch(
                engineers, YearMonth.of(2026, 1), YearMonth.of(2027, 12), LocalDate.of(2026, 1, 1));

        assertEquals(4_800, rows.size());
        verify(workCalendarMapper, times(1)).selectList(any(Wrapper.class));
        verify(leaveRequestMapper, times(1)).selectList(any(Wrapper.class));
        verify(allocationMapper, times(1)).selectList(any(Wrapper.class));
        verify(contractMapper, times(1)).selectList(any(Wrapper.class));
        verify(workCalendarDayMapper, never()).selectList(any(Wrapper.class));
    }
}
