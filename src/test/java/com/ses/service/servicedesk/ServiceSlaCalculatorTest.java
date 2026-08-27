package com.ses.service.servicedesk;

import com.ses.entity.ServiceSlaPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class ServiceSlaCalculatorTest {

    private ServiceSlaCalculator calculator;
    private ServiceSlaPolicy standardPolicy;

    @BeforeEach
    void setUp() {
        calculator = new ServiceSlaCalculator();
        standardPolicy = ServiceSlaPolicy.builder()
                .businessHoursStart(LocalTime.of(9, 0))
                .businessHoursEnd(LocalTime.of(18, 0))
                .includeHolidays(false)
                .build();
    }

    @Test
    @DisplayName("同日営業時間内での期限計算（例: 10:00起票、4時間後 -> 14:00）")
    void testCalculateDeadline_sameDay() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 24, 10, 0); // 2026-08-24 (月)
        LocalDateTime deadline = calculator.calculateDeadline(start, 4, standardPolicy);

        assertEquals(LocalDateTime.of(2026, 8, 24, 14, 0), deadline);
    }

    @Test
    @DisplayName("日跨ぎの期限計算（例: 16:00起票、4時間後 -> 翌営業日 11:00）")
    void testCalculateDeadline_acrossDay() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 24, 16, 0); // 月曜 16:00 (当日残り2時間)
        LocalDateTime deadline = calculator.calculateDeadline(start, 4, standardPolicy);

        // 翌火曜の 09:00 から残り2時間 -> 11:00
        assertEquals(LocalDateTime.of(2026, 8, 25, 11, 0), deadline);
    }

    @Test
    @DisplayName("金曜夕方起票時の週末スキップ計算（例: 金曜 16:00起票、4時間後 -> 翌月曜 11:00）")
    void testCalculateDeadline_weekendSkip() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 28, 16, 0); // 金曜 16:00 (当日残り2時間)
        LocalDateTime deadline = calculator.calculateDeadline(start, 4, standardPolicy);

        // 土日はスキップされ、翌月曜 2026-08-31 09:00 から2時間 -> 11:00
        assertEquals(LocalDateTime.of(2026, 8, 31, 11, 0), deadline);
    }

    @Test
    @DisplayName("営業時間外（深夜・早朝）起票時の始業時刻アライン計算")
    void testCalculateDeadline_outOfHours() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 25, 3, 0); // 火曜 早朝 03:00
        LocalDateTime deadline = calculator.calculateDeadline(start, 2, standardPolicy);

        // 当日 09:00 から2時間 -> 11:00
        assertEquals(LocalDateTime.of(2026, 8, 25, 11, 0), deadline);
    }

    @Test
    @DisplayName("週末起票時の月曜始業時刻アライン計算")
    void testCalculateDeadline_weekendStart() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 29, 12, 0); // 土曜 正午
        LocalDateTime deadline = calculator.calculateDeadline(start, 1, standardPolicy);

        // 翌月曜 09:00 から1時間 -> 10:00
        assertEquals(LocalDateTime.of(2026, 8, 31, 10, 0), deadline);
    }

    @Test
    @DisplayName("一時停止（Pause）期間の延長計算（例: 期限 15:00、停止 120分 -> 17:00）")
    void testCalculateExtendedDeadline_sameDay() {
        LocalDateTime currentDeadline = LocalDateTime.of(2026, 8, 25, 15, 0);
        LocalDateTime extended = calculator.calculateExtendedDeadline(currentDeadline, 120, standardPolicy);

        assertEquals(LocalDateTime.of(2026, 8, 25, 17, 0), extended);
    }

    @Test
    @DisplayName("一時停止（Pause）期間の延長で終業・週末を跨ぐ計算（例: 金曜 17:00期限、停止 120分 -> 翌月曜 10:00）")
    void testCalculateExtendedDeadline_acrossWeekend() {
        LocalDateTime currentDeadline = LocalDateTime.of(2026, 8, 28, 17, 0); // 金曜 17:00 (終業まで1時間)
        LocalDateTime extended = calculator.calculateExtendedDeadline(currentDeadline, 120, standardPolicy);

        // 金曜で1時間進み 18:00、残り1時間は月曜 09:00 から進み 10:00
        assertEquals(LocalDateTime.of(2026, 8, 31, 10, 0), extended);
    }
}
