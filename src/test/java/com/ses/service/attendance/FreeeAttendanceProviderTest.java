package com.ses.service.attendance;

import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.sync.AttendanceMonthlyPayload;
import com.ses.service.attendance.provider.FreeeAttendanceProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * freee勤怠連携は未対応（実freee人事労務APIへの対応表が未設計）。
 *
 * <p>以前は存在しないendpoint（/hr/api/v1/attendance/monthly, /updated）を呼んでいた。
 * 外部APIを一切呼ばず、利用不可として明示的に拒否することを固定する。</p>
 */
class FreeeAttendanceProviderTest {

    private final FreeeAttendanceProvider provider = new FreeeAttendanceProvider();

    @Test
    void 接続状態に関わらず利用不可を返しCSV出力へ誘導する() {
        assertEquals("freee", provider.source());
        assertFalse(provider.available());
    }

    @Test
    void 送信は外部APIを呼ばず未対応エラーで拒否する() {
        BusinessException ex = assertThrows(BusinessException.class, () -> provider.pushMonthly(
                AttendanceMonthlyPayload.builder().engineerId(1L).build(), "att-sync-hash", "corr-1"));
        assertEquals("error.attendance.sync.freeeNotSupported", ex.getMessage());
        assertEquals(400, ex.getCode());
    }

    @Test
    void 照合取得も外部APIを呼ばず未対応エラーで拒否する() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> provider.fetchUpdatedSince("2026-08-10T10:00:00+09:00"));
        assertEquals("error.attendance.sync.freeeNotSupported", ex.getMessage());
    }
}
