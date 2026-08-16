package com.ses.dto.freee.hr;

import lombok.Data;

/**
 * freee人事労務 全期間従業員（公式 `ApiV1CompaniesEmployeeSerializer`）。
 * この画面に不要な銀行口座・住所・家族・生年月日等は要求・保持しない。
 */
@Data
public class FreeeHrEmployee {
    private Long id;
    private String num;
    private String displayName;
    private String entryDate;
    private String retireDate;
    private Boolean payrollCalculation;
}
