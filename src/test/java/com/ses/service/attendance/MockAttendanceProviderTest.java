package com.ses.service.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.sync.AttendanceMonthlyPayload;
import com.ses.dto.attendance.sync.ExternalAttendanceRecord;
import com.ses.service.attendance.provider.MockAttendanceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S11 T072: mock providerの冪等（重複送信で外部1件）と差分取得（cursor）。 */
class MockAttendanceProviderTest {

    private MockAttendanceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MockAttendanceProvider();
    }

    @Test
    void 重複送信で外部1件_同じ冪等キーは2回目false() {
        AttendanceMonthlyPayload payload = AttendanceMonthlyPayload.builder()
                .engineerId(1L)
                .engineerName("山田太郎")
                .workMonth("2026-08")
                .status("承認済")
                .build();
        assertTrue(provider.pushMonthly(payload, "att-sync-key-1", "corr-1"));
        assertFalse(provider.pushMonthly(payload, "att-sync-key-1", "corr-2"),
                "同一payloadの再送は外部1件（冪等キーで重複と判定）");
        assertTrue(provider.pushMonthly(payload, "att-sync-key-2", "corr-3"),
                "別payload（別冪等キー）は受け付ける");
    }

    @Test
    void 差分取得はcursor以降だけを返す() {
        provider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-1").externalEngineerId("emp-1")
                .workDate(LocalDate.of(2026, 8, 1))
                .updatedAt("2026-08-10T10:00:00+09:00").build());
        provider.seedExternalRecord(ExternalAttendanceRecord.builder()
                .sourceExternalId("ext-2").externalEngineerId("emp-1")
                .workDate(LocalDate.of(2026, 8, 2))
                .updatedAt("2026-08-11T10:00:00+09:00").build());

        List<ExternalAttendanceRecord> all = provider.fetchUpdatedSince(null);
        assertEquals(2, all.size(), "cursorなしは全件");

        List<ExternalAttendanceRecord> after = provider.fetchUpdatedSince("2026-08-10T10:00:00+09:00");
        assertEquals(1, after.size());
        assertEquals("ext-2", after.get(0).getSourceExternalId());
    }

    @Test
    void availableは常にtrue() {
        assertTrue(provider.available());
        assertEquals("mock", provider.source());
    }
}
