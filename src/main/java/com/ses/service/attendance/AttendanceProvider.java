package com.ses.service.attendance;

import com.ses.dto.attendance.sync.AttendanceMonthlyPayload;
import com.ses.dto.attendance.sync.ExternalAttendanceRecord;

import java.util.List;

/**
 * 雇用勤怠の外部provider境界（S11 B1）。
 *
 * <p>G6決定により本システムが雇用勤怠の正であり、freee等の外部はdownstream/照合先。
 * 本interfaceは「承認/締め済みデータの冪等送信（push）」と「外部updated_at cursorによる
 * 差分取得（pull・read-only照合）」をprovider非依存で扱う。</p>
 *
 * <p>実装の選択は {@code attendance.sync.provider}（mock=既定 / freee）で切り替える。</p>
 */
public interface AttendanceProvider {

    /** provider識別子（"mock" / "freee"）。 */
    String source();

    /** providerが利用可能か（freee未接続・未設定時はfalse）。 */
    boolean available();

    /**
     * 承認/締め済みの月次勤怠を冪等送信する。
     *
     * @param payload        送信payload
     * @param idempotencyKey payload hashベースの冪等キー（同じpayloadの再送は外部1件）
     * @param correlationId 相関ID
     * @return 外部側で重複と判定された場合はfalse（外部1件の冪等を保証する）
     */
    boolean pushMonthly(AttendanceMonthlyPayload payload, String idempotencyKey, String correlationId);

    /**
     * 外部updated_at cursor以降の差分レコードを取得する（read-only照合）。
     *
     * @param cursor 前回取得時点のcursor（null=全件）
     * @return 外部レコード一覧
     */
    List<ExternalAttendanceRecord> fetchUpdatedSince(String cursor);
}
