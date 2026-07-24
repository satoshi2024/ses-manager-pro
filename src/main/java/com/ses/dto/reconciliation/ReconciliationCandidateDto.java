package com.ses.dto.reconciliation;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 入金1件に対する請求書突合候補（スコア降順で提示）。
 */
@Data
public class ReconciliationCandidateDto {
    private Long invoiceId;
    private String invoiceNo;
    private Long customerId;
    private String customerName;
    private LocalDate dueDate;
    private BigDecimal balance;
    private int score;
    private boolean amountMatch;
    private boolean nameMatch;
    private boolean dateNear;
}
