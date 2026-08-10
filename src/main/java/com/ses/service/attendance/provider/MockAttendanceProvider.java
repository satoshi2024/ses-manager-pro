package com.ses.service.attendance.provider;

import com.ses.dto.attendance.sync.AttendanceMonthlyPayload;
import com.ses.dto.attendance.sync.ExternalAttendanceRecord;
import com.ses.service.attendance.AttendanceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * sandbox/既定のmock provider（{@code attendance.sync.provider=mock}）。
 *
 * <p>実freee APIなしで「冪等送信→外部1件」「差分取得→read-only照合」「締め済み月拒否」の
 * Demoとtestを成立させる。外部側の状態はメモリ上に持ち、payload hashベースの冪等キーで
 * 重複受信を1件にまとめる（重複送信で外部1件）。</p>
 */
@Slf4j
@Component
public class MockAttendanceProvider implements AttendanceProvider {

    /** 外部側で受信済みの冪等キー（payload hash）。 */
    private final Set<String> receivedIdempotencyKeys = ConcurrentHashMap.newKeySet();
    /** Demo/test用に投入する外部レコード（fetchUpdatedSinceの戻り値の元）。 */
    private final List<ExternalAttendanceRecord> externalRecords = new CopyOnWriteArrayList<>();

    @Override
    public String source() {
        return "mock";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean pushMonthly(AttendanceMonthlyPayload payload, String idempotencyKey, String correlationId) {
        boolean accepted = receivedIdempotencyKeys.add(idempotencyKey);
        log.info("mock provider push: accepted={} engineerId={} month={} correlationId={}",
                accepted, payload.getEngineerId(), payload.getWorkMonth(), correlationId);
        return accepted;
    }

    @Override
    public List<ExternalAttendanceRecord> fetchUpdatedSince(String cursor) {
        List<ExternalAttendanceRecord> result = new ArrayList<>();
        for (ExternalAttendanceRecord record : externalRecords) {
            if (cursor == null || record.getUpdatedAt() == null || record.getUpdatedAt().compareTo(cursor) > 0) {
                result.add(record);
            }
        }
        return result;
    }

    /** Demo/test用に外部レコードを投入する（mock特有の操作）。 */
    public void seedExternalRecord(ExternalAttendanceRecord record) {
        externalRecords.add(record);
    }

    /** 受信済み冪等キーをクリアする（mock特有の操作）。 */
    public void reset() {
        receivedIdempotencyKeys.clear();
        externalRecords.clear();
    }
}
