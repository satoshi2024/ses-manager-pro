package com.ses.dto.freee.hr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * freee人事労務 賞与明細（公式 `ApiV1BonusesEmployeePayrollStatementSerializer`）。
 * 給与と別endpoint。明細は `allowances` / `deductions`。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FreeeBonusStatement {
    private Long id;
    private Long companyId;
    private Long employeeId;
    private String employeeNum;
    private String payDate;
    private Boolean fixed;
    private String calcStatus;
    private String grossPaymentAmount;
    private String totalDeductionAmount;
    private String netPaymentAmount;
    private List<FreeePayrollItem> allowances = new ArrayList<>();
    private List<FreeePayrollItem> deductions = new ArrayList<>();
}
