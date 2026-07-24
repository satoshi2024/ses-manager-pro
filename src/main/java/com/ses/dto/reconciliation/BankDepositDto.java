package com.ses.dto.reconciliation;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * freeeから取得した銀行入金明細1件（永続化前の生データ）。
 */
@Data
public class BankDepositDto {
    private String freeeDepositId;
    private LocalDate depositDate;
    private BigDecimal amount;
    private String payerName;
}
