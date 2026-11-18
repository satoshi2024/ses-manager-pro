package com.ses.service.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.sync.AttendanceMonthlyPayload;
import com.ses.dto.attendance.sync.ExternalAttendanceRecord;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.attendance.provider.FreeeAttendanceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * freee勤怠連携（月次サマリ送信・日次勤怠の照合取得）。
 *
 * <p>freeeのサマリは給与支払月で管理されるため、勤務月の1日〜末日を集計期間に持つサマリを探して送ること、
 * 期間が合わなければ送らないこと、休憩時刻を作り出さないことを固定する。</p>
 */
class FreeeAttendanceProviderTest {

    private static final long COMPANY_ID = 7001L;
    private final ObjectMapper om = new ObjectMapper();
    private FreeeIntegrationService service;
    private FreeeEmployeeLinkMapper linkMapper;
    private FreeeAttendanceProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(FreeeIntegrationService.class);
        linkMapper = mock(FreeeEmployeeLinkMapper.class);
        ObjectProvider<FreeeIntegrationService> serviceProvider = mock(ObjectProvider.class);
        when(serviceProvider.getIfAvailable()).thenReturn(service);
        provider = new FreeeAttendanceProvider(serviceProvider, linkMapper);
        when(service.connectedCompanyId()).thenReturn(COMPANY_ID);
    }

    @Test
    void 未接続なら利用不可() {
        when(service.connectedCompanyId()).thenReturn(null);
        assertEquals("freee", provider.source());
        assertFalse(provider.available());
        assertThrows(BusinessException.class, () -> provider.pushMonthly(payload(), "k", "c"));
    }

    @Test
    void 勤務月を集計期間に持つ給与支払月のサマリへ送る() throws Exception {
        linked();
        summary(2026, 10, "2026-09-01", "2026-09-30", 0);
        summary(2026, 11, "2026-10-01", "2026-10-31", 0);

        assertTrue(provider.pushMonthly(payload(), "att-sync-x", "corr-1"));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(service).hrApiPut(eq("/api/v1/employees/901/work_record_summaries/2026/11"), body.capture(), eq("corr-1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) body.getValue();
        assertEquals(COMPANY_ID, sent.get("company_id"));
        assertEquals(2.0, sent.get("work_days"));
        assertEquals(1080, sent.get("total_work_mins"));
        // 法定内960分のうち所定900分までが所定労働、超えた60分が法定内残業
        assertEquals(900, sent.get("total_normal_work_mins"));
        assertEquals(60, sent.get("total_overtime_within_statutory_work_mins"));
        assertEquals(120, sent.get("total_overtime_excess_statutory_work_mins"));
        assertEquals(0, sent.get("total_holiday_work_mins"));
        assertEquals(30, sent.get("total_latenight_work_mins"));
        // 休憩の時刻・日次勤怠は送らない
        assertFalse(sent.containsKey("work_records"));
        assertFalse(sent.containsKey("break_records"));
    }

    @Test
    void 登録済みと同じ値なら再送しない() throws Exception {
        linked();
        when(service.hrApiGet(eq("/api/v1/employees/901/work_record_summaries/2026/10"), anyMap()))
                .thenReturn(om.readTree("""
                        {"start_date":"2026-10-01","end_date":"2026-10-31","work_days":2.0,"total_work_mins":1080,
                         "total_normal_work_mins":900,"total_overtime_within_statutory_work_mins":60,
                         "total_overtime_except_normal_work_mins":120,"total_overtime_within_normal_work_mins":0,
                         "total_holiday_work_mins":0,"total_latenight_work_mins":30}
                        """));

        assertFalse(provider.pushMonthly(payload(), "att-sync-x", "corr-1"));
        verify(service, never()).hrApiPut(anyString(), any(), any());
    }

    @Test
    void 締め日が月末でなく期間が合わなければ送らない() throws Exception {
        linked();
        summary(2026, 10, "2026-09-21", "2026-10-20", 0);
        summary(2026, 11, "2026-10-21", "2026-11-20", 0);
        summary(2026, 12, "2026-11-21", "2026-12-20", 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> provider.pushMonthly(payload(), "att-sync-x", "corr-1"));
        assertEquals("error.attendance.sync.freeePeriodMismatch", ex.getMessage());
        verify(service, never()).hrApiPut(anyString(), any(), any());
    }

    @Test
    void 従業員が未対応付けなら送らない() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> provider.pushMonthly(payload(), "att-sync-x", "corr-1"));
        assertEquals("error.attendance.sync.freeeEmployeeNotLinked", ex.getMessage());
    }

    @Test
    void 書込権限不足はfreeeのエラーのまま伝える() throws Exception {
        linked();
        summary(2026, 10, "2026-10-01", "2026-10-31", 0);
        when(service.hrApiPut(anyString(), any(), any()))
                .thenThrow(BusinessException.of(403, "error.payroll.permissionDenied"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> provider.pushMonthly(payload(), "att-sync-x", "corr-1"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 照合取得は出退勤が入力された日次だけを返す() throws Exception {
        FreeeEmployeeLink link = link();
        when(linkMapper.selectList(any())).thenReturn(List.of(link));
        JsonNode node = om.readTree("""
                {"start_date":"2026-10-01","end_date":"2026-10-31","work_records":[
                  {"date":"2026-10-01","clock_in_at":"2026-10-01T09:00:00.000+09:00",
                   "clock_out_at":"2026-10-01T19:00:00.000+09:00","day_pattern":"normal_day",
                   "break_records":[{"clock_in_at":"2026-10-01T12:00:00.000+09:00","clock_out_at":"2026-10-01T13:00:00.000+09:00"}],
                   "total_overtime_except_normal_work_mins":60,"total_holiday_work_mins":0,"total_latenight_work_mins":0},
                  {"date":"2026-10-02","clock_in_at":null,"clock_out_at":null,"day_pattern":"normal_day","break_records":[]}
                ]}
                """);
        when(service.hrApiGet(eq("/api/v1/employees/901/work_record_summaries/2026/10"), anyMap())).thenReturn(node);

        List<ExternalAttendanceRecord> records = provider.fetchForMonth(YearMonth.of(2026, 10), null);

        assertEquals(1, records.size());
        ExternalAttendanceRecord r = records.get(0);
        assertEquals(55L, r.getEngineerId());
        assertEquals(LocalDate.of(2026, 10, 1), r.getWorkDate());
        assertEquals(LocalTime.of(9, 0), r.getClockIn());
        assertEquals(LocalTime.of(19, 0), r.getClockOut());
        assertEquals(60, r.getBreakMinutes());
        assertEquals(480, r.getRegularMinutes());
        assertEquals(60, r.getOvertimeMinutes());
        assertEquals("freee:901:2026-10-01", r.getSourceExternalId());
        // 更新日時の無い月単位スナップショット
        assertEquals(null, r.getUpdatedAt());
    }

    private void linked() {
        when(linkMapper.selectOne(any())).thenReturn(link());
    }

    private FreeeEmployeeLink link() {
        FreeeEmployeeLink link = new FreeeEmployeeLink();
        link.setEngineerId(55L);
        link.setFreeeEmployeeId("901");
        link.setFreeeCompanyId(COMPANY_ID);
        return link;
    }

    private void summary(int year, int month, String start, String end, int workMins) throws Exception {
        when(service.hrApiGet(eq("/api/v1/employees/901/work_record_summaries/" + year + "/" + month), anyMap()))
                .thenReturn(om.readTree("{\"start_date\":\"" + start + "\",\"end_date\":\"" + end
                        + "\",\"total_work_mins\":" + workMins + "}"));
    }

    private static AttendanceMonthlyPayload payload() {
        return AttendanceMonthlyPayload.builder()
                .engineerId(55L)
                .workMonth("2026-10")
                .status("承認済")
                .scheduledMinutes(900)
                .workedMinutes(1080)
                .regularMinutes(960)
                .overtimeMinutes(120)
                .holidayMinutes(0)
                .lateNightMinutes(30)
                .days(List.of(
                        AttendanceMonthlyPayload.Day.builder().workDate("2026-10-01").clockIn("09:00").clockOut("19:00")
                                .breakMinutes(60).regularMinutes(480).overtimeMinutes(60).build(),
                        AttendanceMonthlyPayload.Day.builder().workDate("2026-10-02").clockIn("09:00").clockOut("19:00")
                                .breakMinutes(60).regularMinutes(480).overtimeMinutes(60).lateNightMinutes(30).build(),
                        AttendanceMonthlyPayload.Day.builder().workDate("2026-10-03").build()))
                .build();
    }
}
