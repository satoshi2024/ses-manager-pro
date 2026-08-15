package com.ses.dto.payroll;
import lombok.Data;
/**
 * freee従業員（給与画面用）。公式 `ApiV1CompaniesEmployeeSerializer` の必要最小fieldのみ。
 * 銀行口座・住所・家族・生年月日等は要求・保持しない。BP判定は本システム側で行う。
 * linkState: UNLINKED / LINKED / RECONFIRM_REQUIRED
 */
@Data public class FreeeEmployeeDto {
    private String id;
    private String num;
    private String displayName;
    private String entryDate;
    private String retireDate;
    private Boolean payrollCalculation;
    private String linkState;
    private Long linkedEngineerId;
    private String linkedEngineerName;
}
