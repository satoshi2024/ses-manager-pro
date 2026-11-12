package com.ses.service.attendance.provider;

import com.ses.common.exception.BusinessException;
import com.ses.dto.attendance.sync.AttendanceMonthlyPayload;
import com.ses.dto.attendance.sync.ExternalAttendanceRecord;
import com.ses.service.attendance.AttendanceProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * freee provider（{@code attendance.sync.provider=freee}）。
 *
 * <p><b>未対応のため常に利用不可。</b>以前の実装は {@code /hr/api/v1/attendance/monthly} と
 * {@code /hr/api/v1/attendance/updated} を呼んでいたが、どちらもfreee人事労務APIに存在しない（provisional mapping）。
 * 実APIは従業員・日付単位の勤怠（{@code /api/v1/employees/{id}/work_records/{date}}）と月次サマリ
 * （{@code /api/v1/employees/{id}/work_record_summaries/{year}/{month}}）で、従業員対応付け・締め・差分取得の
 * 設計が別途必要になる。それまでは存在しないendpointを呼ばず、{@link #available()} を false にして
 * CSV出力経路（G6 fallback）へ誘導する。</p>
 */
@Component
public class FreeeAttendanceProvider implements AttendanceProvider {

    static final String NOT_SUPPORTED_KEY = "error.attendance.sync.freeeNotSupported";

    @Override
    public String source() {
        return "freee";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean pushMonthly(AttendanceMonthlyPayload payload, String idempotencyKey, String correlationId) {
        throw BusinessException.of(400, NOT_SUPPORTED_KEY);
    }

    @Override
    public List<ExternalAttendanceRecord> fetchUpdatedSince(String cursor) {
        throw BusinessException.of(400, NOT_SUPPORTED_KEY);
    }
}
