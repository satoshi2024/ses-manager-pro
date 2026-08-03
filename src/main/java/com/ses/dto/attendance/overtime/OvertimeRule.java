package com.ses.dto.attendance.overtime;

/**
 * overtime-rules.md §1 のルール番号。{@code AGREEMENT_MISSING}はルールではなく、
 * 第1段（{@code m_overtime_agreement}）の行が無く判定不能であることを表す。
 */
public enum OvertimeRule {
    RULE1_MONTH_NORMAL,
    RULE2_YEAR_NORMAL,
    RULE3_YEAR_SPECIAL,
    RULE4_MONTH_TOTAL,
    RULE5_MULTI_MONTH_AVERAGE,
    RULE6_EXCEED_MONTH_COUNT,
    AGREEMENT_MISSING
}
