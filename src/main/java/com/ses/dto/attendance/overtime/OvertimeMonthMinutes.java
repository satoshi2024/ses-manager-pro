package com.ses.dto.attendance.overtime;

import java.time.YearMonth;

/**
 * 1か月分の時間外実績（分単位）。
 *
 * <p>{@code overtimeMinutes}は法定休日労働を含まない時間外労働（所定休日労働は含む）。
 * {@code totalMinutes}は法定休日労働を含む合計（overtime-rules.md §1.2）。
 * どちらのfieldを読むかが「休日労働を含むか」の唯一の分岐点であり、
 * {@link com.ses.service.attendance.overtime.OvertimeComplianceCalculator}の各ルールは
 * この2 fieldのどちらかを選ぶだけで、休日区分の判定ロジックを持たない。</p>
 */
public record OvertimeMonthMinutes(YearMonth yearMonth, int overtimeMinutes, int totalMinutes) {
}
