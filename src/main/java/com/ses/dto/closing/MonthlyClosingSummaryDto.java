package com.ses.dto.closing;

import com.ses.dto.WorkRecordGridDto;
import com.ses.dto.invoice.BpPaymentListDto;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.entity.WorkRecord;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 月次締めチェックリストの集計結果（5項目 + 締め状態）。
 */
@Data
public class MonthlyClosingSummaryDto {
    private String month;

    /** (a) 工数未入力の稼動契約。 */
    private List<WorkRecordGridDto> unenteredWork;
    /** (b) 入力中のまま残っている実績（未確定）。 */
    private List<WorkRecord> unconfirmedRecords;
    
    @Data
    public static class CustomerUnbilledDto {
        private Long customerId;
        private String customerName;
        private BigDecimal subtotal;
        private List<UnbilledWorkRecordDto> items;
    }
    
    /** (c) 確定済み未請求実績（全顧客）。顧客単位でグループ化。 */
    private List<CustomerUnbilledDto> unbilledConfirmed;
    
    /** (d) 未払BP。 */
    private List<BpPaymentListDto> unpaidBp;
    /** (e) 期限超過請求（残高付き）。 */
    private List<InvoiceBalanceDto> overdueInvoices;
    /** (f) 労務コンプライアンスリスク該当契約（labor-compliance-check / FR-10）。締めを妨げない。 */
    private List<com.ses.dto.compliance.ContractComplianceDto> complianceFindings;

    /** (g) 未検収件数（R4.2）: 閲覧者のscopeで数える（design §5.2）。 */
    private int unacceptedCount;

    /** (h) 会計連携の未解消差異件数（R3.3/B3）。 */
    private int accountingDiscrepancyCount;

    private int unenteredCount;
    private int unconfirmedCount;
    private int unbilledCount;
    private int unpaidBpCount;
    private int overdueCount;
    private int complianceCount;

    /** (a)-(d) が全て0で締め可能か。 */
    private boolean readyToClose;

    /** 締め済みか。 */
    private boolean closed;
    private Long closedBy;
    private String closedByName;
    private LocalDateTime closedAt;
}