package com.ses.service.attendance.overtime;

import com.ses.dto.attendance.overtime.OvertimeComplianceFinding;

import java.time.YearMonth;
import java.util.List;

/**
 * 36協定コンプライアンス判定の本番配線（月次締め / scheduler）。
 * {@link OvertimeComplianceCalculator}の結果を{@code t_overtime_followup}へUPSERTし、
 * R3.3の段階通知を発行する。
 */
public interface OvertimeComplianceService {

    /**
     * 対象要員×月を判定し、findingを永続化する。通知は{@code @Async}（ObjectProvider経由）。
     *
     * @return 判定結果（適合ルールは空、違反・判定不能のみ）
     */
    List<OvertimeComplianceFinding> evaluateAndPersist(Long engineerId, YearMonth targetMonth);

    /**
     * 対象月の承認済/締め済月次を一括判定する（scheduler principal）。
     *
     * @return 処理した月次行数
     */
    int evaluateApprovedOrClosedMonths(YearMonth targetMonth);

    /** 段階通知の非同期実行。呼び出し側は必ずObjectProvider経由で呼ぶこと。 */
    void notifyFindingsAsync(Long engineerId, YearMonth targetMonth, List<OvertimeComplianceFinding> findings);
}
