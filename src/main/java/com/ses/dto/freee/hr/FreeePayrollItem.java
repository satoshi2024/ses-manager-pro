package com.ses.dto.freee.hr;

import lombok.Data;

/**
 * freee人事労務 給与明細の項目（公式 `ApiV1EmployeePayrollStatementsEmployeePayrollStatementItemSerializer`）。
 * amountはJSON string。nullは計算中を意味し、0とは区別する。
 */
@Data
public class FreeePayrollItem {
    private String name;
    private String amount;
}
