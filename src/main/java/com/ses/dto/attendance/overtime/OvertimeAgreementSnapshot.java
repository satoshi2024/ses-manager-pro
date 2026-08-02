package com.ses.dto.attendance.overtime;

import com.ses.service.attendance.overtime.OvertimeAgreementThresholds;

/**
 * {@link OvertimeAgreementThresholds} の値保持実装。DBアクセスを一切行わない単純なvalue object。
 * F1（{@code m_overtime_agreement}、V78）実装後は、当該テーブルの行から本レコードを組み立てるか、
 * interfaceを直接実装する形で置き換わる想定。
 */
public record OvertimeAgreementSnapshot(
        boolean specialClauseEnabled,
        Integer monthNormalLimitMinutes,
        Integer yearNormalLimitMinutes,
        Integer yearSpecialLimitMinutes,
        Integer monthTotalLimitMinutes,
        Integer multiMonthAverageLimitMinutes,
        Integer exceedMonthCountLimit
) implements OvertimeAgreementThresholds {
}
