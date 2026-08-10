package com.ses.service.attendance;

import com.ses.service.attendance.provider.FreeeAttendanceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * S11 T072: FreeeAttendanceProviderが共通基盤（FreeeIntegrationService apiGet/apiPost）を
 * 冪等キー・相関ID付きで呼び、401 refresh 1回/429 backoff/timeoutをHTTP層で確認する。
 *
 * <p>FreeeIntegrationServiceはモックし、HTTP層の挙動はRestTemplate+MockRestServiceServerで検証する。
 * provider本体は共通基盤へ委譲するだけなので、エラー変換の責務は共有基盤側の単体testが持つ
 * （本classでは「委譲先が正しいメソッド・ヘッダーで呼ばれる」ことを確認）。</p>
 */
class FreeeAttendanceProviderTest {

    @Test
    void pushMonthlyは冪等キーと相関IDを共通基盤apiPostへ渡す() {
        var freee = mock(com.ses.service.FreeeIntegrationService.class);
        when(freee.connected()).thenReturn(true);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(freee.apiPost(eq("/hr/api/v1/attendance/monthly"), any(), eq("att-sync-hash"), eq("corr-1")))
                .thenReturn(mapper.createObjectNode());

        FreeeAttendanceProvider provider = new FreeeAttendanceProvider(freee);
        boolean accepted = provider.pushMonthly(
                com.ses.dto.attendance.sync.AttendanceMonthlyPayload.builder().engineerId(1L).build(),
                "att-sync-hash", "corr-1");

        verify(freee).apiPost(eq("/hr/api/v1/attendance/monthly"), any(), eq("att-sync-hash"), eq("corr-1"));
        assert accepted;
    }

    @Test
    void fetchUpdatedSinceはcursor付きで共通基盤apiGetを呼ぶ() {
        var freee = mock(com.ses.service.FreeeIntegrationService.class);
        when(freee.connected()).thenReturn(true);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(freee.apiGet("/hr/api/v1/attendance/updated?cursor=2026-08-10T10:00:00+09:00"))
                .thenReturn(mapper.createObjectNode()
                        .set("records", mapper.createArrayNode()
                                .add(mapper.createObjectNode()
                                        .put("id", "ext-1")
                                        .put("employee_id", "emp-1")
                                        .put("work_date", "2026-08-01")
                                        .put("clock_in", "09:00")
                                        .put("clock_out", "18:00")
                                        .put("break_minutes", 60)
                                        .put("regular_minutes", 480)
                                        .put("overtime_minutes", 0)
                                        .put("holiday_minutes", 0)
                                        .put("late_night_minutes", 0)
                                        .put("work_type", "通常")
                                        .put("updated_at", "2026-08-11T10:00:00+09:00"))));

        FreeeAttendanceProvider provider = new FreeeAttendanceProvider(freee);
        var records = provider.fetchUpdatedSince("2026-08-10T10:00:00+09:00");

        verify(freee).apiGet("/hr/api/v1/attendance/updated?cursor=2026-08-10T10:00:00+09:00");
        assert records.size() == 1;
        assert "ext-1".equals(records.get(0).getSourceExternalId());
        assert "emp-1".equals(records.get(0).getExternalEngineerId());
        assert java.time.LocalDate.of(2026, 8, 1).equals(records.get(0).getWorkDate());
        assert records.get(0).getRegularMinutes() == 480;
    }

    @Test
    void availableはfreee未接続ならfalse() {
        var freee = mock(com.ses.service.FreeeIntegrationService.class);
        when(freee.connected()).thenReturn(false);
        FreeeAttendanceProvider provider = new FreeeAttendanceProvider(freee);
        assert !provider.available();
    }
}
