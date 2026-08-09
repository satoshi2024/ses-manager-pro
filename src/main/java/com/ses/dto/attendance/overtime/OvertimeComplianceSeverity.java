package com.ses.dto.attendance.overtime;

/** VIOLATION=ルール違反。INDETERMINATE=必要な正本・履歴が無く判定不能（fail-closed）。 */
public enum OvertimeComplianceSeverity {
    VIOLATION,
    INDETERMINATE
}
