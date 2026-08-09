package com.ses.service.attendance;

import com.ses.entity.WorkCalendar;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** T070 R2-P1-02の日次法定時間・休日・深夜・calendar優先順位の直接回帰。 */
@ExtendWith(MockitoExtension.class)
class AttendanceCalculatorTest {

    @Mock
    private WorkCalendarMapper workCalendarMapper;

    @Mock
    private WorkCalendarDayMapper workCalendarDayMapper;

    @InjectMocks
    private AttendanceCalculator calculator;

    private WorkCalendar legalCalendar;

    @BeforeEach
    void setUp() {
        legalCalendar = WorkCalendar.builder().legalEntityId(100L)
                .organizationId(10L).name("法人calendar").validFrom(LocalDate.of(2026, 1, 1))
                .status("有効").build();
        legalCalendar.setId(1L);
        when(workCalendarMapper.selectList(any())).thenReturn(List.of(legalCalendar));
    }

    @Test
    void 通常日8時間超と22時境界を分離する() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(23, 0), 0, 0);

        assertEquals(840, result.workedMinutes());
        assertEquals(480, result.regularMinutes());
        assertEquals(360, result.overtimeMinutes());
        assertEquals(0, result.holidayMinutes());
        assertEquals(60, result.lateNightMinutes());
    }

    @Test
    void 跨夜と休憩を深夜から差し引く() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(22, 0), LocalTime.of(5, 0), 60, 0);

        assertEquals(360, result.workedMinutes());
        assertEquals(360, result.regularMinutes());
        assertEquals(0, result.overtimeMinutes());
        assertEquals(360, result.lateNightMinutes());
    }

    @Test
    void 所定休日は時間外で法定休日は休日へ分離する() {
        day("所定休日", null);
        AttendanceCalculation scheduledHoliday = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(18, 0), 60, 0);
        assertEquals(480, scheduledHoliday.overtimeMinutes());
        assertEquals(0, scheduledHoliday.holidayMinutes());

        day("法定休日", null);
        AttendanceCalculation legalHoliday = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(18, 0), 60, 0);
        assertEquals(0, legalHoliday.overtimeMinutes());
        assertEquals(480, legalHoliday.holidayMinutes());
    }

    @Test
    void 週40時間を超えた日だけ時間外へ繰り越す() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 7), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(17, 0), 0, 2400);

        assertEquals(0, result.regularMinutes());
        assertEquals(480, result.overtimeMinutes());
    }

    @Test
    void 個人calendarを法人calendarより優先しscheduledのNULLと0を保持する() {
        WorkCalendar engineerCalendar = WorkCalendar.builder().legalEntityId(100L)
                .engineerId(20L).name("個人calendar").validFrom(LocalDate.of(2026, 1, 1))
                .status("有効").build();
        engineerCalendar.setId(3L);
        when(workCalendarMapper.selectList(any())).thenReturn(List.of(legalCalendar, engineerCalendar));
        day("通常", null);
        AttendanceCalculation nullScheduled = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(10, 0), 0, 0);
        assertEquals(3L, nullScheduled.workCalendarId());
        assertEquals(null, nullScheduled.scheduledMinutes());

        day("通常", 0);
        AttendanceCalculation zeroScheduled = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(10, 0), 0, 0);
        assertEquals(0, zeroScheduled.scheduledMinutes());
    }

    private void day(String type, Integer scheduledMinutes) {
        when(workCalendarDayMapper.selectOne(any())).thenReturn(WorkCalendarDay.builder()
                .calendarId(1L).calendarDate(LocalDate.of(2026, 8, 3))
                .dayType(type).scheduledMinutes(scheduledMinutes).build());
    }
}
