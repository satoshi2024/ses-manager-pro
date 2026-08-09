package com.ses.dto.attendance.overtime;

import com.ses.service.attendance.overtime.OvertimeAgreementThresholds;
import com.ses.entity.OvertimeAgreement;

/**
 * {@link OvertimeAgreementThresholds} の値保持実装。DBアクセスを一切行わない単純なvalue object。
 * F1で追加した{@code m_overtime_agreement}（V83）の対象月行から組み立てる。
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

    /** DB上の法人別協定行をcalculator入力用のsnapshotへ変換する。 */
    public static OvertimeAgreementSnapshot from(OvertimeAgreement agreement) {
        if (agreement == null) {
            return null;
        }
        return new OvertimeAgreementSnapshot(
                Integer.valueOf(1).equals(agreement.getSpecialClause()),
                agreement.getNormalMonthLimitMinutes(),
                agreement.getNormalYearLimitMinutes(),
                agreement.getSpecialYearLimitMinutes(),
                agreement.getTotalMonthLimitMinutes(),
                agreement.getMultiMonthAverageLimitMinutes(),
                agreement.getExceedMonthCountLimit());
    }
}
