package com.ses.dto.accounting.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 売上請求の標準DTO (Canonical Model)。
 * freee 取引APIおよび CSV エクスポートの共通入力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalSalesInvoice {
    private Long invoiceId;
    private String invoiceNo;
    private Long customerId;
    private String customerCode;
    /**
     * 照合済みCUSTOMER_PARTNERマッピングのfreee取引先ID。freeeへは partner_id として送る。
     * freeeの取引先コードは事業所設定で無効化されていることがあるため、内部コード（customerCode）は送らない。
     */
    private String partnerExternalId;
    private String customerName;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;
    private BigDecimal taxRate;
    private String sectionCode;
    private String remarks;
    private List<Detail> details;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Detail {
        private String description;
        /** 税込金額。 */
        private BigDecimal amount;
        /** 明細の消費税額（請求書の消費税額を明細へ按分した値。合計は請求書の消費税額と一致する）。旧snapshotはnull。 */
        private BigDecimal vat;
        private BigDecimal taxRate;
        private String accountItemCode;
        private String taxCode;
    }
}
