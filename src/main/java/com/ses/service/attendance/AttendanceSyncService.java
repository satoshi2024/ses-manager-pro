package com.ses.service.attendance;

import com.ses.dto.attendance.sync.AttendanceSyncResultDto;

import java.io.OutputStream;

/** 雇用勤怠の外部provider同期（S11 B1）。 */
public interface AttendanceSyncService {

    /**
     * 承認/締め済みの月次勤怠を外部へ冪等送信する。
     *
     * @param month 対象月（yyyy-MM）
     */
    AttendanceSyncResultDto syncPush(String month);

    /**
     * 外部updated_at cursorで差分を取得し、read-only照合用レコードとして登録する。
     * 締め済み・承認済み月への外部更新は拒否してfinding（t_overtime_followup＋通知）にする。
     *
     * @param month 対象月（yyyy-MM）
     */
    AttendanceSyncResultDto syncPull(String month);

    /** push＋pullの両方を実行する。 */
    AttendanceSyncResultDto syncAll(String month);

    /** 最後の実行結果（error UI / status API用）。未実行なら空の結果を返す。 */
    AttendanceSyncResultDto lastResult();

    /** providerが利用可能か（freee未接続時はCSV経路を案内する）。 */
    boolean providerAvailable();

    /** 現在のprovider識別子（"mock" / "freee"）。 */
    String providerSource();

    /**
     * 承認/締め済みの月次勤怠をCSV出力する（G6 fallback。freee API利用不可時）。
     * 画面/APIと同じscope母集団を適用する。
     */
    void exportCsv(String month, OutputStream out);
}
