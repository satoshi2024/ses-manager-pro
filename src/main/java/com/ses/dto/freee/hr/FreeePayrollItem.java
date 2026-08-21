package com.ses.dto.freee.hr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * freee人事労務 給与明細の項目（公式 `ApiV1EmployeePayrollStatementsEmployeePayrollStatementItemSerializer`）。
 * amountはJSON string。nullは計算中を意味し、0とは区別する。
 * 未知の追加propertyは許容する（freee側の後方互換拡張でHTTP 500にしない）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FreeePayrollItem {
    private String name;
    private String amount;
}
