package com.ses.dto.invoice;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 請求書×入金合計の残高付き一覧行。
 * 残高 = total - Σ(amount+fee)。エイジング区分振り分けは Java 側で行う。
 */
@Data
public class InvoiceBalanceDto {
    private Long invoiceId;
    private String invoiceNo;
    private Long customerId;
    private String customerName;
    private String billingMonth;
    private String status;
    private BigDecimal total;
    /** 入金合計(手数料込み)。入金行が無い場合は 0。 */
    private BigDecimal paidTotal;
    /** 残高 = total - paidTotal。 */
    private BigDecimal balance;
    private LocalDate dueDate;
}
