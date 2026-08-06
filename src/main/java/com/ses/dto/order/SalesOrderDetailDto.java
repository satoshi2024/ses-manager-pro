package com.ses.dto.order;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 注文詳細DTO（ヘッダ・明細・見積/契約との差分・文書リンクを含む）。 */
@Data
public class SalesOrderDetailDto {
    private Long id;
    private String orderNo;
    private String customerPoNo;
    private Long customerId;
    private String customerName;
    private Long contactId;
    private String contactName;
    private Long quotationId;
    private String quotationNo;
    private LocalDate orderDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private BigDecimal totalAmountSnapshot;
    private String paymentTermsSnapshot;
    private Long sourceDocumentId;
    private Long acknowledgementDocumentId;
    private Integer version;
    private List<Line> lines;
    /** 見積/契約との条件差分（R2.3）。空=差分なし。 */
    private List<DiffItem> diffs;

    @Data
    public static class Line {
        private Long id;
        private Integer lineNo;
        private Long projectId;
        private String projectName;
        private Long engineerId;
        private String engineerName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal settlementMin;
        private BigDecimal settlementMax;
        private BigDecimal amount;
        private String remarks;
        /** この明細から生成済みの契約ID（1明細→1契約）。無ければnull。 */
        private Long contractId;
        private String contractNo;
    }

    @Data
    public static class DiffItem {
        private String field;
        private String label;
        private String before;
        private String after;
        /** 差分の対象（QUOTATION / CONTRACT） */
        private String target;
    }
}
