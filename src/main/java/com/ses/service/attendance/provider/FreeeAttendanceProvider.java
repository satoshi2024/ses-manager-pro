package com.ses.service.attendance.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.sync.AttendanceMonthlyPayload;
import com.ses.dto.attendance.sync.ExternalAttendanceRecord;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.attendance.AttendanceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * freee provider実装（{@code attendance.sync.provider=freee}）。
 *
 * <p>OAuth/refresh・401 refresh 1回・429 backoff・timeout変換・冪等キー/相関IDヘッダーは
 * {@link FreeeIntegrationService}のapiGet/apiPost共通基盤を再利用する（design §3）。
 * 実freee APIの勤怠エンドポイント形状は本番release gate（G4）であり、本実装はprovisional mapping。
 * 未接続時は {@link #available()} がfalseを返し、CSV出力経路（G6 fallback）へフォールバックする。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreeeAttendanceProvider implements AttendanceProvider {

    private final FreeeIntegrationService freeeIntegrationService;

    @Override
    public String source() {
        return "freee";
    }

    @Override
    public boolean available() {
        return freeeIntegrationService.connected();
    }

    @Override
    public boolean pushMonthly(AttendanceMonthlyPayload payload, String idempotencyKey, String correlationId) {
        if (!available()) {
            throw BusinessException.of("error.payroll.notConnected");
        }
        JsonNode response = freeeIntegrationService.apiPost(
                "/hr/api/v1/attendance/monthly", payload, idempotencyKey, correlationId);
        // 外部側が冪等キーで重複と判定した場合の応答（provisional）。受け付けなかったらfalse。
        return response == null || !response.path("duplicate").asBoolean(false);
    }

    @Override
    public List<ExternalAttendanceRecord> fetchUpdatedSince(String cursor) {
        if (!available()) {
            throw BusinessException.of("error.payroll.notConnected");
        }
        String path = "/hr/api/v1/attendance/updated" + (cursor == null || cursor.isBlank() ? "" : "?cursor=" + cursor);
        JsonNode response = freeeIntegrationService.apiGet(path);
        List<ExternalAttendanceRecord> result = new ArrayList<>();
        if (response == null || !response.has("records")) {
            return result;
        }
        for (JsonNode node : response.path("records")) {
            result.add(toRecord(node));
        }
        return result;
    }

    private ExternalAttendanceRecord toRecord(JsonNode node) {
        ExternalAttendanceRecord record = new ExternalAttendanceRecord();
        record.setSourceExternalId(node.path("id").asText());
        record.setExternalEngineerId(node.path("employee_id").asText());
        record.setWorkDate(LocalDate.parse(node.path("work_date").asText()));
        if (node.hasNonNull("clock_in")) {
            record.setClockIn(LocalTime.parse(node.path("clock_in").asText()));
        }
        if (node.hasNonNull("clock_out")) {
            record.setClockOut(LocalTime.parse(node.path("clock_out").asText()));
        }
        record.setBreakMinutes(intValue(node, "break_minutes"));
        record.setRegularMinutes(intValue(node, "regular_minutes"));
        record.setOvertimeMinutes(intValue(node, "overtime_minutes"));
        record.setHolidayMinutes(intValue(node, "holiday_minutes"));
        record.setLateNightMinutes(intValue(node, "late_night_minutes"));
        record.setWorkType(node.path("work_type").asText("通常"));
        record.setUpdatedAt(node.path("updated_at").asText());
        return record;
    }

    private Integer intValue(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.path(key).asInt() : null;
    }
}
