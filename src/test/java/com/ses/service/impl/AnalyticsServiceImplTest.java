package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.analytics.BenchEngineerDto;
import com.ses.dto.analytics.UtilizationPointDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceImplTest {

    @Mock
    private EngineerMapper engineerMapper;

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private EngineerSkillMapper engineerSkillMapper;

    private AnalyticsServiceImpl analyticsService;

    private Engineer createEngineer(Long id, String fullName, String status, LocalDateTime createdAt) {
        Engineer e = new Engineer();
        e.setId(id);
        e.setFullName(fullName);
        e.setStatus(status);
        e.setCreatedAt(createdAt);
        return e;
    }

    private Contract createContract(Long engineerId, String status, LocalDate startDate, LocalDate endDate) {
        Contract c = new Contract();
        c.setEngineerId(engineerId);
        c.setStatus(status);
        c.setStartDate(startDate);
        c.setEndDate(endDate);
        return c;
    }

    private void initService() {
        analyticsService = new AnalyticsServiceImpl(engineerMapper, contractMapper, engineerSkillMapper);
    }

    // --- utilizationTrend boundary tests ---

    @Test
    void utilizationTrend_contractStartingExactlyOnMonthEnd_countedAsActiveThatMonth() {
        initService();
        // Current month reference point
        YearMonth targetMonth = YearMonth.from(LocalDate.now());
        LocalDate monthEnd = targetMonth.atEndOfMonth();

        Engineer e1 = createEngineer(1L, "Eng1", "稼動中", monthEnd.minusMonths(6).atStartOfDay());
        when(engineerMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(e1));

        // Contract starts exactly on the last day of the target month -> should count as active that month
        Contract c1 = createContract(1L, "稼動中", monthEnd, null);
        when(contractMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(c1));

        List<UtilizationPointDto> result = analyticsService.utilizationTrend(1);
        assertEquals(1, result.size());
        UtilizationPointDto point = result.get(0);
        assertEquals(1, point.getActiveCount());
        assertEquals(1, point.getTotalCount());
        assertEquals(0, point.getBenchCount());
        assertEquals(100.0, point.getUtilizationRate());
    }

    @Test
    void utilizationTrend_contractEndingExactlyOnMonthEnd_stillActiveThatMonth_excludedNextMonth() {
        initService();
        YearMonth currentMonth = YearMonth.from(LocalDate.now());
        YearMonth previousMonth = currentMonth.minusMonths(1);
        LocalDate previousMonthEnd = previousMonth.atEndOfMonth();

        Engineer e1 = createEngineer(1L, "Eng1", "稼動中", previousMonthEnd.minusMonths(6).atStartOfDay());
        when(engineerMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(e1));

        // Contract ends exactly on the last day of the previous month.
        Contract c1 = createContract(1L, "終了", previousMonthEnd.minusMonths(3), previousMonthEnd);
        when(contractMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(c1));

        List<UtilizationPointDto> result = analyticsService.utilizationTrend(2);
        assertEquals(2, result.size());

        UtilizationPointDto previousMonthPoint = result.get(0);
        assertEquals(previousMonth.toString(), previousMonthPoint.getYearMonth());
        assertEquals(1, previousMonthPoint.getActiveCount(), "終了日が月末と同日の契約はその月は稼動扱いとする");

        UtilizationPointDto currentMonthPoint = result.get(1);
        assertEquals(currentMonth.toString(), currentMonthPoint.getYearMonth());
        assertEquals(0, currentMonthPoint.getActiveCount(), "契約終了月の翌月からは非稼動扱いとする");
    }

    @Test
    void utilizationTrend_zeroEngineers_utilizationRateIsNullNoDivideByZero() {
        initService();
        when(engineerMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(contractMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        List<UtilizationPointDto> result = analyticsService.utilizationTrend(3);
        assertEquals(3, result.size());
        for (UtilizationPointDto point : result) {
            assertEquals(0, point.getTotalCount());
            assertEquals(0, point.getActiveCount());
            assertEquals(0, point.getBenchCount());
            assertNull(point.getUtilizationRate());
        }
    }

    @Test
    void utilizationTrend_singleFetchOfEngineersAndContracts() {
        initService();
        when(engineerMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(contractMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        analyticsService.utilizationTrend(12);

        org.mockito.Mockito.verify(engineerMapper, org.mockito.Mockito.times(1)).selectList(any(QueryWrapper.class));
        org.mockito.Mockito.verify(contractMapper, org.mockito.Mockito.times(1)).selectList(any(QueryWrapper.class));
    }

    // --- benchList tests ---

    @Test
    void benchList_engineerWithPastContract_benchDaysCalculatedFromLastEndDate() {
        initService();
        LocalDate endDate = LocalDate.now().minusDays(45);
        Engineer bench1 = createEngineer(1L, "Bench Eng", "Bench", LocalDate.now().minusYears(1).atStartOfDay());
        when(engineerMapper.selectList(any())).thenReturn(List.of(bench1));

        Contract oldContract = createContract(1L, "終了", endDate.minusMonths(6), endDate);
        when(contractMapper.selectList(any())).thenReturn(List.of(oldContract));
        lenient().when(engineerSkillMapper.selectDetailByEngineerId(anyLong())).thenReturn(Collections.emptyList());

        List<BenchEngineerDto> result = analyticsService.benchList();
        assertEquals(1, result.size());
        BenchEngineerDto dto = result.get(0);
        assertEquals(45, dto.getBenchDays());
        assertTrue(dto.getSkillNames().isEmpty());
    }

    @Test
    void benchList_engineerWithoutAnyContract_fallsBackToCreatedAt() {
        initService();
        LocalDate createdDate = LocalDate.now().minusDays(10);
        Engineer bench1 = createEngineer(2L, "New Bench Eng", "Bench", createdDate.atStartOfDay());
        when(engineerMapper.selectList(any())).thenReturn(List.of(bench1));
        when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(engineerSkillMapper.selectDetailByEngineerId(anyLong())).thenReturn(Collections.emptyList());

        List<BenchEngineerDto> result = analyticsService.benchList();
        assertEquals(1, result.size());
        assertEquals(10, result.get(0).getBenchDays());
    }

    @Test
    void benchList_sortedByBenchDaysDescending() {
        initService();
        Engineer e1 = createEngineer(1L, "Eng1", "Bench", LocalDate.now().minusDays(5).atStartOfDay());
        Engineer e2 = createEngineer(2L, "Eng2", "Bench", LocalDate.now().minusDays(100).atStartOfDay());
        when(engineerMapper.selectList(any())).thenReturn(List.of(e1, e2));
        when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(engineerSkillMapper.selectDetailByEngineerId(anyLong())).thenReturn(Collections.emptyList());

        List<BenchEngineerDto> result = analyticsService.benchList();
        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).getEngineerId());
        assertEquals(1L, result.get(1).getEngineerId());
    }

    @Test
    void benchList_empty_returnsEmptyList() {
        initService();
        when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<BenchEngineerDto> result = analyticsService.benchList();
        assertTrue(result.isEmpty());
    }

    @Test
    void benchList_expectedUnitPriceAndAvailableDatePropagated() {
        initService();
        Engineer e1 = createEngineer(1L, "Eng1", "Bench", LocalDate.now().minusDays(1).atStartOfDay());
        e1.setExpectedUnitPrice(BigDecimal.valueOf(650000));
        e1.setAvailableDate(LocalDate.of(2026, 8, 1));
        when(engineerMapper.selectList(any())).thenReturn(List.of(e1));
        when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(engineerSkillMapper.selectDetailByEngineerId(anyLong())).thenReturn(Collections.emptyList());

        List<BenchEngineerDto> result = analyticsService.benchList();
        assertEquals(1, result.size());
        assertEquals(BigDecimal.valueOf(650000), result.get(0).getExpectedUnitPrice());
        assertEquals(LocalDate.of(2026, 8, 1), result.get(0).getAvailableDate());
    }
}
