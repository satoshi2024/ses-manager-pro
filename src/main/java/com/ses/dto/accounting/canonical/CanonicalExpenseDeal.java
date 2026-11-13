package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 要員経費申請の標準DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalExpenseDeal {
    private Long expenseRequestId;
    private Long expenseId;
    private String expenseNo;
    private Long engineerId;
    private String engineerCode;
    private String engineerName;
    private LocalDate expenseDate;
    private String category;
    private BigDecimal amount;
    private BigDecimal taxRate;
    private String accountItemCode;
    private String taxCode;
    private String sectionCode;
    private String description;

    /**
     * 前の世代の送信ジョブが外部へ送信した後に取消された（freeeに取引が作成されたか確定できない）。
     * trueのときproviderは作成前に同じ管理番号の取引が無いことを確認し、あれば作成しない（二重作成防止）。
     * 通常の送信ではnull（スナップショットに出力しない）。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean verifyNoExistingDeal;
}
