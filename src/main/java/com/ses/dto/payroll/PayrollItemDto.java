package com.ses.dto.payroll;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 給与・賞与明細の項目。区分と名前を失わない。
 * category: PAYMENT（支給）/ DEDUCTION（控除）/ EMPLOYER_SHARE（会社負担）/ ALLOWANCE（賞与手当）
 */
@Data
public class PayrollItemDto {
    private String category;
    private String name;
    private BigDecimal amount;
}
