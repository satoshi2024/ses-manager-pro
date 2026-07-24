package com.ses.dto.reconciliation;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 未消込の入金1件（分類＋突合候補付き）。
 * classification: "candidate"（候補あり・人が確定） / "pending"（候補なし・保留）。
 */
@Data
public class PendingDepositDto {
    private Long depositId;
    private LocalDate depositDate;
    private BigDecimal amount;
    private String payerName;
    private String classification;
    private List<ReconciliationCandidateDto> candidates;
}
