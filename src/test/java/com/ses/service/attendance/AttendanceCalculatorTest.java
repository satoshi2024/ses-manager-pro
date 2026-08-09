package com.ses.service.attendance;

import com.ses.common.exception.BusinessException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T070 R2-P1-02の日次法定時間・休日・深夜・calendar優先順位と、
 * 方式A（design §5.1.1）の休憩区間intersectionの直接回帰。
 */
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
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(23, 0), List.of(), 0);

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
                100L, 10L, LocalTime.of(22, 0), LocalTime.of(5, 0),
                List.of(new AttendanceCalculator.BreakInterval(60, 120)), 0);

        assertEquals(360, result.workedMinutes());
        assertEquals(360, result.regularMinutes());
        assertEquals(0, result.overtimeMinutes());
        assertEquals(360, result.lateNightMinutes());
    }

    @Test
    void 所定休日は時間外で法定休日は休日へ分離する() {
        day("所定休日", null);
        AttendanceCalculation scheduledHoliday = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new AttendanceCalculator.BreakInterval(180, 240)), 0);
        assertEquals(480, scheduledHoliday.overtimeMinutes());
        assertEquals(0, scheduledHoliday.holidayMinutes());

        day("法定休日", null);
        AttendanceCalculation legalHoliday = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new AttendanceCalculator.BreakInterval(180, 240)), 0);
        assertEquals(0, legalHoliday.overtimeMinutes());
        assertEquals(480, legalHoliday.holidayMinutes());
    }

    @Test
    void 週40時間を超えた日だけ時間外へ繰り越す() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 7), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(17, 0), List.of(), 2400);

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
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(10, 0), List.of(), 0);
        assertEquals(3L, nullScheduled.workCalendarId());
        assertEquals(null, nullScheduled.scheduledMinutes());

        day("通常", 0);
        AttendanceCalculation zeroScheduled = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(10, 0), List.of(), 0);
        assertEquals(0, zeroScheduled.scheduledMinutes());
    }

    @Test
    void 別要員の個人calendarを法人fallbackへ混入させない() {
        WorkCalendar otherEngineerCalendar = WorkCalendar.builder().legalEntityId(100L)
                .engineerId(99L).name("別要員calendar").validFrom(LocalDate.of(2026, 1, 1))
                .status("有効").build();
        otherEngineerCalendar.setId(9L);
        when(workCalendarMapper.selectList(any())).thenReturn(List.of(otherEngineerCalendar, legalCalendar));
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(10, 0), List.of(), 0);

        assertEquals(1L, result.workCalendarId());
    }

    @Test
    void 対象組織calendarを他組織と法人既定より優先する() {
        WorkCalendar targetOrganizationCalendar = WorkCalendar.builder().legalEntityId(100L)
                .organizationId(10L).name("対象組織calendar").validFrom(LocalDate.of(2026, 1, 1))
                .status("有効").build();
        targetOrganizationCalendar.setId(11L);
        WorkCalendar otherOrganizationCalendar = WorkCalendar.builder().legalEntityId(100L)
                .organizationId(11L).name("他組織calendar").validFrom(LocalDate.of(2026, 2, 1))
                .status("有効").build();
        otherOrganizationCalendar.setId(12L);
        WorkCalendar legalFallback = WorkCalendar.builder().legalEntityId(100L)
                .name("法人既定calendar").validFrom(LocalDate.of(2026, 3, 1))
                .status("有効").build();
        legalFallback.setId(13L);
        when(workCalendarMapper.selectList(any())).thenReturn(List.of(),
                List.of(otherOrganizationCalendar, targetOrganizationCalendar), List.of(legalFallback));
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(10, 0), List.of(), 0);

        assertEquals(11L, result.workCalendarId());
    }

    @Test
    void 深夜前の休憩は実休憩位置で深夜を保持する() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(21, 0), LocalTime.of(23, 0),
                List.of(new AttendanceCalculator.BreakInterval(0, 60)), 0);

        assertEquals(60, result.workedMinutes());
        assertEquals(60, result.regularMinutes());
        assertEquals(0, result.overtimeMinutes());
        assertEquals(60, result.lateNightMinutes());
    }

    @Test
    void 深夜中の休憩は深夜時間だけを差し引く() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(22, 0), LocalTime.of(5, 0),
                List.of(new AttendanceCalculator.BreakInterval(120, 180)), 0);

        assertEquals(360, result.workedMinutes());
        assertEquals(360, result.regularMinutes());
        assertEquals(360, result.lateNightMinutes());
    }

    @Test
    void 深夜後の休憩は深夜前の実労働だけを深夜に数える() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(21, 0), LocalTime.of(23, 0),
                List.of(new AttendanceCalculator.BreakInterval(90, 120)), 0);

        assertEquals(90, result.workedMinutes());
        assertEquals(90, result.regularMinutes());
        assertEquals(30, result.lateNightMinutes());
    }

    @Test
    void 跨夜休憩は勤務開始基準のoffsetで一意に表す() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(22, 0), LocalTime.of(5, 0),
                List.of(new AttendanceCalculator.BreakInterval(60, 180)), 0);

        assertEquals(300, result.workedMinutes());
        assertEquals(300, result.regularMinutes());
        assertEquals(300, result.lateNightMinutes());
    }

    @Test
    void 複数休憩の合計を実労働から差し引く() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new AttendanceCalculator.BreakInterval(180, 240),
                        new AttendanceCalculator.BreakInterval(360, 375)), 0);

        assertEquals(465, result.workedMinutes());
        assertEquals(465, result.regularMinutes());
        assertEquals(0, result.lateNightMinutes());
    }

    @Test
    void 休憩0分の区間なしは実働と一致する() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(18, 0), List.of(), 0);

        assertEquals(540, result.workedMinutes());
        assertEquals(480, result.regularMinutes());
        assertEquals(60, result.overtimeMinutes());
    }

    @Test
    void 全時間休憩は実働0分で許可される() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(10, 0),
                List.of(new AttendanceCalculator.BreakInterval(0, 60)), 0);

        assertEquals(0, result.workedMinutes());
        assertEquals(0, result.regularMinutes());
    }

    @Test
    void 重複する休憩区間は拒否する() {
        day("通常", 480);

        BusinessException e = assertThrows(BusinessException.class, () -> calculator.calculate(
                LocalDate.of(2026, 8, 3), 20L, 100L, 10L,
                LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new AttendanceCalculator.BreakInterval(0, 60),
                        new AttendanceCalculator.BreakInterval(30, 90)), 0));

        assertEquals("error.attendance.breakOverlap", e.getMessageKey());
    }

    @Test
    void 隣接する休憩区間は許可する() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new AttendanceCalculator.BreakInterval(0, 60),
                        new AttendanceCalculator.BreakInterval(60, 120)), 0);

        assertEquals(420, result.workedMinutes());
        assertEquals(420, result.regularMinutes());
    }

    @Test
    void 勤務区間外の休憩区間は拒否する() {
        day("通常", 480);

        BusinessException e = assertThrows(BusinessException.class, () -> calculator.calculate(
                LocalDate.of(2026, 8, 3), 20L, 100L, 10L,
                LocalTime.of(9, 0), LocalTime.of(10, 0),
                List.of(new AttendanceCalculator.BreakInterval(30, 90)), 0));

        assertEquals("error.attendance.breakOutOfRange", e.getMessageKey());
    }

    @Test
    void 開始が終了以上の休憩区間は拒否する() {
        day("通常", 480);

        BusinessException e = assertThrows(BusinessException.class, () -> calculator.calculate(
                LocalDate.of(2026, 8, 3), 20L, 100L, 10L,
                LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new AttendanceCalculator.BreakInterval(120, 120)), 0));

        assertEquals("error.attendance.breakInvalid", e.getMessageKey());
    }

    @Test
    void 休憩合計が勤務時間を超える場合は拒否する() {
        day("通常", 480);

        BusinessException e = assertThrows(BusinessException.class, () -> calculator.calculate(
                LocalDate.of(2026, 8, 3), 20L, 100L, 10L,
                LocalTime.of(9, 0), LocalTime.of(11, 0),
                List.of(new AttendanceCalculator.BreakInterval(0, 70),
                        new AttendanceCalculator.BreakInterval(50, 120)), 0));

        assertEquals("error.attendance.breakTotalExceeds", e.getMessageKey());
    }

    @Test
    void 休憩を挟んだ8時間境界を実労働で判定する() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 3), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(18, 30),
                List.of(new AttendanceCalculator.BreakInterval(180, 240)), 0);

        assertEquals(510, result.workedMinutes());
        assertEquals(480, result.regularMinutes());
        assertEquals(30, result.overtimeMinutes());
    }

    @Test
    void 週40時間境界は休憩を挟んだ実労働で判定する() {
        day("通常", 480);

        AttendanceCalculation result = calculator.calculate(LocalDate.of(2026, 8, 7), 20L,
                100L, 10L, LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new AttendanceCalculator.BreakInterval(180, 240)), 2400);

        assertEquals(480, result.workedMinutes());
        assertEquals(0, result.regularMinutes());
        assertEquals(480, result.overtimeMinutes());
    }

    private void day(String type, Integer scheduledMinutes) {
        when(workCalendarDayMapper.selectOne(any())).thenReturn(WorkCalendarDay.builder()
                .calendarId(1L).calendarDate(LocalDate.of(2026, 8, 3))
                .dayType(type).scheduledMinutes(scheduledMinutes).build());
    }
}
