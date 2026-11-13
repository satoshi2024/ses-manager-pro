package com.ses.service.attendance.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.sync.AttendanceMonthlyPayload;
import com.ses.dto.attendance.sync.ExternalAttendanceRecord;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.attendance.AttendanceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * freee人事労務 provider（{@code attendance.sync.provider=freee}）。
 *
 * <p><b>送信（push）</b>は承認・締め済みの月次勤怠を月次サマリ
 * （{@code PUT /api/v1/employees/{id}/work_record_summaries/{year}/{month}}）として上書き登録する。
 * 日次勤怠（work_records）は休憩の開始・終了時刻が必須だが、本システムは休憩を分数でしか持たないため使わない
 * （時刻を作り出さない）。freeeの給与計算はこのサマリの時間を使う。</p>
 *
 * <p>freeeのサマリは<b>給与支払月</b>で管理される（例: 月末締め翌月払いなら10月勤務分は11月のサマリ）。
 * そのため対象勤務月と同じ月・翌月・翌々月のサマリを照会し、集計期間（start_date〜end_date）が勤務月の
 * 1日〜末日と一致するものへ送る。一致するサマリが無い（締め日が月末でない等）場合は、期間の違う合計を
 * 送らずにエラーにする。</p>
 *
 * <p><b>取得（pull）</b>は同じサマリを {@code work_records=true} で取得し、出退勤が入力された日次勤怠だけを
 * 照合用レコードとして返す（freeeの日次勤怠には更新日時が無いため、月単位のスナップショット）。</p>
 *
 * <p>従業員の対応付けは給与連携の {@code t_freee_employee_link}（接続中の事業所のもの）を使う。
 * 送信には人事労務の勤怠「書込」権限が必要で、不足時は {@code error.payroll.permissionDenied} になる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreeeAttendanceProvider implements AttendanceProvider {

    static final String PERIOD_MISMATCH_KEY = "error.attendance.sync.freeePeriodMismatch";
    static final String NOT_CONNECTED_KEY = "error.attendance.sync.freeeNotConnected";
    /** 勤務月から何か月先までの給与支払月を照会するか（当月払い・翌月払い・翌々月払い）。 */
    static final int MAX_PAY_MONTH_OFFSET = 2;

    private final ObjectProvider<FreeeIntegrationService> freeeIntegrationService;
    private final FreeeEmployeeLinkMapper freeeEmployeeLinkMapper;

    @Override
    public String source() {
        return "freee";
    }

    @Override
    public boolean available() {
        FreeeIntegrationService service = freeeIntegrationService.getIfAvailable();
        if (service == null) {
            return false;
        }
        try {
            return service.connectedCompanyId() != null;
        } catch (RuntimeException e) {
            log.warn("freee勤怠: 接続状態を確認できません: exceptionClass={}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean pushMonthly(AttendanceMonthlyPayload payload, String idempotencyKey, String correlationId) {
        FreeeIntegrationService service = service();
        long companyId = companyId(service);
        String employeeId = linkedEmployeeId(payload.getEngineerId(), companyId);
        YearMonth workMonth = YearMonth.parse(payload.getWorkMonth());
        Summary summary = findSummary(service, companyId, employeeId, workMonth, false);

        Map<String, Object> body = summaryBody(companyId, payload);
        if (summary.sameAs(body)) {
            // 同じ値が既に登録済み（再送）。外部は上書きにならないため重複として数える
            return false;
        }
        service.hrApiPut("/api/v1/employees/" + employeeId + "/work_record_summaries/"
                + summary.payMonth().getYear() + "/" + summary.payMonth().getMonthValue(), body, correlationId);
        log.info("freee勤怠サマリを登録しました: engineerId={} workMonth={} payMonth={} correlationId={}",
                payload.getEngineerId(), workMonth, summary.payMonth(), correlationId);
        return true;
    }

    @Override
    public List<ExternalAttendanceRecord> fetchUpdatedSince(String cursor) {
        // freeeの日次勤怠には更新日時のcursorが無い。同期serviceは月を指定する fetchForMonth を使う
        throw new IllegalStateException("freee勤怠はfetchForMonthで取得します");
    }

    @Override
    public List<ExternalAttendanceRecord> fetchForMonth(YearMonth month, String cursor) {
        FreeeIntegrationService service = service();
        long companyId = companyId(service);
        List<FreeeEmployeeLink> links = freeeEmployeeLinkMapper.selectList(new LambdaQueryWrapper<FreeeEmployeeLink>()
                .eq(FreeeEmployeeLink::getFreeeCompanyId, companyId));
        List<ExternalAttendanceRecord> records = new ArrayList<>();
        for (FreeeEmployeeLink link : links) {
            if (link.getFreeeEmployeeId() == null || link.getEngineerId() == null) {
                continue;
            }
            Summary summary;
            try {
                summary = findSummary(service, companyId, link.getFreeeEmployeeId(), month, true);
            } catch (BusinessException e) {
                if (PERIOD_MISMATCH_KEY.equals(e.getMessage())) {
                    // この従業員は対象月のサマリを持たない（入社前・退職後等）。照合対象なし
                    continue;
                }
                throw e;
            }
            for (JsonNode day : summary.node().path("work_records")) {
                ExternalAttendanceRecord record = dailyRecord(link, day);
                if (record != null && YearMonth.from(record.getWorkDate()).equals(month)) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    /** freeeの日次勤怠1件を照合用レコードへ変換する。出退勤が入力されていない日（既定値だけの日）はnull。 */
    static ExternalAttendanceRecord dailyRecord(FreeeEmployeeLink link, JsonNode day) {
        LocalDateTime in = dateTime(day.path("clock_in_at").asText(null));
        LocalDateTime out = dateTime(day.path("clock_out_at").asText(null));
        LocalDate date = date(day.path("date").asText(null));
        if (in == null || out == null || date == null) {
            return null;
        }
        int breakMinutes = 0;
        for (JsonNode b : day.path("break_records")) {
            LocalDateTime bs = dateTime(b.path("clock_in_at").asText(null));
            LocalDateTime be = dateTime(b.path("clock_out_at").asText(null));
            if (bs != null && be != null && be.isAfter(bs)) {
                breakMinutes += (int) Duration.between(bs, be).toMinutes();
            }
        }
        int worked = Math.max(0, (int) Duration.between(in, out).toMinutes() - breakMinutes);
        int holiday = day.path("total_holiday_work_mins").asInt(0);
        int overtime = day.path("total_overtime_except_normal_work_mins").asInt(
                day.path("total_overtime_work_mins").asInt(0));
        String dayPattern = day.path("day_pattern").asText("");
        if ("prescribed_holiday".equals(dayPattern)) {
            // 本システムは所定休日の労働を時間外として数える（AttendanceCalculator）
            overtime = worked;
        }
        int regular = "legal_holiday".equals(dayPattern) || "prescribed_holiday".equals(dayPattern)
                ? 0 : Math.max(0, worked - overtime - holiday);
        return ExternalAttendanceRecord.builder()
                .sourceExternalId("freee:" + link.getFreeeEmployeeId() + ":" + date)
                .externalEngineerId(link.getFreeeEmployeeId())
                .engineerId(link.getEngineerId())
                .workDate(date)
                .clockIn(in.toLocalTime().withSecond(0).withNano(0))
                .clockOut(out.toLocalTime().withSecond(0).withNano(0))
                .breakMinutes(breakMinutes)
                .regularMinutes(regular)
                .overtimeMinutes(overtime)
                .holidayMinutes(holiday)
                .lateNightMinutes(day.path("total_latenight_work_mins").asInt(0))
                .updatedAt(null)
                .build();
    }

    /**
     * freeeの月次サマリへ送る値。本システムの「法定内」は1日8時間・週40時間以内の労働で、freeeでは
     * 所定労働時間と法定内残業に分かれる。日別の所定時間を持たないため月単位で分ける
     * （所定時間までを所定労働、超えた分を法定内残業）。法定外・法定休日・深夜はそのまま対応する。
     */
    static Map<String, Object> summaryBody(long companyId, AttendanceMonthlyPayload payload) {
        int regular = value(payload.getRegularMinutes());
        int scheduled = value(payload.getScheduledMinutes());
        int normal = scheduled > 0 ? Math.min(regular, scheduled) : regular;
        int withinStatutory = regular - normal;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("company_id", companyId);
        body.put("work_days", workDays(payload));
        body.put("total_work_mins", value(payload.getWorkedMinutes()));
        body.put("total_normal_work_mins", normal);
        body.put("total_overtime_within_statutory_work_mins", withinStatutory);
        body.put("total_overtime_excess_statutory_work_mins", value(payload.getOvertimeMinutes()));
        body.put("total_holiday_work_mins", value(payload.getHolidayMinutes()));
        body.put("total_latenight_work_mins", value(payload.getLateNightMinutes()));
        return body;
    }

    static double workDays(AttendanceMonthlyPayload payload) {
        if (payload.getDays() == null) {
            return 0;
        }
        return payload.getDays().stream()
                .filter(d -> d.getClockIn() != null
                        || value(d.getRegularMinutes()) + value(d.getOvertimeMinutes()) + value(d.getHolidayMinutes()) > 0)
                .map(AttendanceMonthlyPayload.Day::getWorkDate)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    /**
     * 勤務月の1日〜末日を集計期間に持つサマリを、給与支払月 = 勤務月〜勤務月+2か月 の順に探す。
     * 照会できない月（404）は飛ばし、どれも一致しなければ期間不一致として拒否する。
     */
    private Summary findSummary(FreeeIntegrationService service, long companyId, String employeeId,
                                YearMonth workMonth, boolean withDailyRecords) {
        LocalDate first = workMonth.atDay(1);
        LocalDate last = workMonth.atEndOfMonth();
        for (int offset = 0; offset <= MAX_PAY_MONTH_OFFSET; offset++) {
            YearMonth payMonth = workMonth.plusMonths(offset);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("company_id", String.valueOf(companyId));
            if (withDailyRecords) {
                query.put("work_records", "true");
            }
            JsonNode node;
            try {
                node = service.hrApiGet("/api/v1/employees/" + employeeId + "/work_record_summaries/"
                        + payMonth.getYear() + "/" + payMonth.getMonthValue(), query);
            } catch (BusinessException e) {
                if (e.getCode() == 404) {
                    continue;
                }
                throw e;
            }
            if (node == null) {
                continue;
            }
            LocalDate start = date(node.path("start_date").asText(null));
            LocalDate end = date(node.path("end_date").asText(null));
            if (first.equals(start) && last.equals(end)) {
                return new Summary(payMonth, node);
            }
        }
        throw BusinessException.of(400, PERIOD_MISMATCH_KEY);
    }

    private FreeeIntegrationService service() {
        FreeeIntegrationService service = freeeIntegrationService.getIfAvailable();
        if (service == null) {
            throw BusinessException.of(400, NOT_CONNECTED_KEY);
        }
        return service;
    }

    private long companyId(FreeeIntegrationService service) {
        Long companyId = service.connectedCompanyId();
        if (companyId == null) {
            throw BusinessException.of(400, NOT_CONNECTED_KEY);
        }
        return companyId;
    }

    private String linkedEmployeeId(Long engineerId, long companyId) {
        FreeeEmployeeLink link = engineerId == null ? null : freeeEmployeeLinkMapper.selectOne(
                new LambdaQueryWrapper<FreeeEmployeeLink>()
                        .eq(FreeeEmployeeLink::getEngineerId, engineerId)
                        .eq(FreeeEmployeeLink::getFreeeCompanyId, companyId)
                        .last("LIMIT 1"));
        if (link == null || link.getFreeeEmployeeId() == null || link.getFreeeEmployeeId().isBlank()) {
            throw BusinessException.of(400, NOT_LINKED_KEY);
        }
        return link.getFreeeEmployeeId();
    }

    private static int value(Integer v) {
        return v == null ? 0 : v;
    }

    static LocalDate date(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return text.length() > 10 ? dateTime(text).toLocalDate() : LocalDate.parse(text);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    /** freeeの日時（"2026-10-01T09:00:00.000+09:00" または "2026-10-01 09:00:00"）を現地時刻として読む。 */
    static LocalDateTime dateTime(String text) {
        if (text == null || text.isBlank() || "null".equals(text)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(text.replace(' ', 'T'));
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /** 照会したサマリ（給与支払月と応答）。 */
    record Summary(YearMonth payMonth, JsonNode node) {

        /** 送る値がすべて登録済みの値と同じか（再送の重複判定）。freeeの応答は法定外を2項目に分けて返す。 */
        boolean sameAs(Map<String, Object> body) {
            int overtime = node.path("total_overtime_except_normal_work_mins").asInt(0)
                    + node.path("total_overtime_within_normal_work_mins").asInt(0);
            return node.path("work_days").asDouble(-1) == ((Number) body.get("work_days")).doubleValue()
                    && node.path("total_work_mins").asInt(-1) == (int) body.get("total_work_mins")
                    && node.path("total_normal_work_mins").asInt(-1) == (int) body.get("total_normal_work_mins")
                    && node.path("total_overtime_within_statutory_work_mins").asInt(-1)
                    == (int) body.get("total_overtime_within_statutory_work_mins")
                    && overtime == (int) body.get("total_overtime_excess_statutory_work_mins")
                    && node.path("total_holiday_work_mins").asInt(-1) == (int) body.get("total_holiday_work_mins")
                    && node.path("total_latenight_work_mins").asInt(-1) == (int) body.get("total_latenight_work_mins");
        }
    }
}
