package com.ses.dto.payroll;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 給与・賞与明細（読み取り専用・永続化しない）。
 *
 * <ul>
 *   <li>金額はnullを保持する（計算中）。0へ変換しない</li>
 *   <li>itemsは区分付きlist。同名項目も別要素として保持する（Map上書き禁止）</li>
 *   <li>返却対象は現在companyの有効linkを持つ非BP・未削除の内部要員だけ</li>
 * </ul>
 */
@Data
public class PayrollStatementDto {
    private Long engineerId;
    private String engineerName;
    private String employeeId;
    private String employeeNumber;
    private Integer year;
    private Integer month;
    private String type;
    private String payDate;
    private Boolean fixed;
    /** 公式calc_status（calculating/calculated/overwritten/imported/error）。 */
    private String calculationStatus;
    private BigDecimal grossAmount;
    private BigDecimal deductionAmount;
    private BigDecimal netAmount;
    /** 給与のみ。賞与はnull。 */
    private BigDecimal employerShareAmount;
    private List<PayrollItemDto> items;
}
