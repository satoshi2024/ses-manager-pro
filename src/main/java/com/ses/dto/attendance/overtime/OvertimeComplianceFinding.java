package com.ses.dto.attendance.overtime;

import java.time.YearMonth;

/**
 * {@code OvertimeComplianceCalculator}が返すfinding。適合ルールはfindingを生成しない
 * （{@code LaborComplianceService}と同じく、問題があるものだけを返す）。
 *
 * @param windowMonths ルール5（複数月平均）でどのnか月窓が違反したかを示す。他ルールでは{@code null}
 */
public record OvertimeComplianceFinding(
        OvertimeRule rule,
        OvertimeComplianceSeverity severity,
        Long legalEntityId,
        YearMonth targetMonth,
        Integer actualMinutes,
        Integer limitMinutes,
        Integer windowMonths
) {
}
