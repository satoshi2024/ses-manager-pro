package com.ses.dto.freee.hr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * freee人事労務 給与明細（公式 `ApiV1SalariesEmployeePayrollStatementSerializer`）。
 * 金額はJSON stringでnullable。rootは `employee_payroll_statements`、件数は `total_count`。
 * 未知の追加propertyは許容する（後方互換）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FreeeSalaryStatement {
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
    private String totalDeductionEmployerShare;
    private List<FreeePayrollItem> payments = new ArrayList<>();
    private List<FreeePayrollItem> deductions = new ArrayList<>();
    private List<FreeePayrollItem> deductionsEmployerShare = new ArrayList<>();
}
