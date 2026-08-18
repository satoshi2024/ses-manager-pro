package com.ses.dto.accounting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会計・支払月次照合 DTO (design §5, §6.3)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingReconciliationSummaryDto {
    private String month;
    private int matchedCount;
    private int internalOnlyCount;
    private int externalOnlyCount;
    private int amountMismatchCount;
    private int ignoredCount;
    private boolean readyForClosing;
    private boolean externalFetchFailed;
    private List<ReconciliationItemDto> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReconciliationItemDto {
        private String category; // SALES / PURCHASE / PAYMENT
        private Long internalId;
        private String internalNo;
        private String partnerName;
        private BigDecimal internalAmount;
        private String externalDealId;
        private String externalRefNo;
        private BigDecimal externalAmount;
        private String status; // MATCHED / INTERNAL_ONLY / EXTERNAL_ONLY / AMOUNT_MISMATCH / IGNORED
        private String discrepancyReason;
        private String ignoreReason;
    }
}
