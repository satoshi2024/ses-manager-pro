package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * BP仕入（再委託費用）の標準DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalPurchaseDeal {
    private Long bpPaymentId;
    private Long workRecordId;
    private Long bpCompanyId;
    private String bpCompanyCode;
    /** 照合済みBP_PARTNERマッピングのfreee取引先ID。freeeへは partner_id として送る。 */
    private String partnerExternalId;
    private String bpCompanyName;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private BigDecimal amount;
    private BigDecimal taxRate;
    private String accountItemCode;
    private String taxCode;
    private String sectionCode;
    private String remarks;

    /**
     * 前の世代の送信ジョブが外部へ送信した後に取消された（freeeに取引が作成されたか確定できない）。
     * trueのときproviderは作成前に同じ管理番号の取引が無いことを確認し、あれば作成しない（二重作成防止）。
     * 通常の送信ではnull（スナップショットに出力しない）。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean verifyNoExistingDeal;

    /**
     * 外部へ送信した後に取消された前の世代ごとの走査範囲（取引先・発生日）。既存取引の確認は、
     * 今回の取引先・発生日に加えてこの範囲も走査する（取消の前に別の取引先・発生日で作成された取引を見落とさない）。
     * 通常の送信では空（スナップショットに出力しない）。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    private List<PriorDealScope> priorDealScopes;
}
