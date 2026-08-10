package com.ses.service.attendance;

import com.ses.dto.attendance.discrepancy.AttendanceDiscrepancyDto;

/** 客先工数差異（B2）。read-only比較と理由確認。 */
public interface AttendanceDiscrepancyService {

    /**
     * 対象月の客先工数差異を返す（read-only。金額計算・請求ロジックへ接続しない）。
     * scope: 管理者=全件、HR=法人scope、マネージャー=組織scope。
     */
    AttendanceDiscrepancyDto list(String month);

    /**
     * 差異の確認理由を保存する。確認しても請求金額は変わらない（R4.2）。
     * 保存先はm_system_config JSON（新規テーブル不可のため、closing.confirmed-months前例）。
     */
    void confirm(Long engineerId, String month, String reason);

    /**
     * 閾値超過かつ未確認の差異一覧を全件で返す（scheduler principal相当、scope非依存）。
     * 日次バッチのwarning/escalation通知（design §5.3 scheduler principal=全件）が使う。
     */
    AttendanceDiscrepancyDto pendingWarnings(String month);
}
