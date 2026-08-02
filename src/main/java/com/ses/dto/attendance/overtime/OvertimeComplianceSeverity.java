package com.ses.dto.attendance.overtime;

/** VIOLATION=ルール違反。INDETERMINATE=協定行が無く判定不能（fail-closed、既定で「適合」にしない）。 */
public enum OvertimeComplianceSeverity {
    VIOLATION,
    INDETERMINATE
}
