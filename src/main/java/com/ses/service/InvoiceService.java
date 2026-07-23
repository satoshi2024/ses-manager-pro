package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.InvoiceDetailDto;
import com.ses.dto.invoice.AgingReportDto;
import com.ses.dto.mail.MailDispatchResult;
import com.ses.entity.Invoice;
import com.ses.entity.MailDelivery;
import com.ses.entity.InvoicePayment;
import com.ses.dto.invoice.InvoicePaymentCreateRequest;
import com.ses.dto.invoice.InvoicePaymentResponse;

import java.time.LocalDate;
import java.util.List;

public interface InvoiceService extends IService<Invoice> {
    Invoice generate(Long customerId, String billingMonth);
    String generateInvoiceNo(String billingMonth);
    void changeStatus(Long id, String status, LocalDate paidDate);
    void changeBpPaymentStatus(Long id, String status, LocalDate paidDate);
    void voidInvoice(Long id);
    InvoiceDetailDto detail(Long id);

    // ===== 債権管理（ar-management / P2） =====
    /** 入金を登録し、消込ステータスを再計算する（過入金・取消済み請求書は拒否）。 */
    InvoicePaymentResponse addPayment(Long invoiceId, InvoicePaymentCreateRequest request);
    /** 入金を削除し、消込ステータスを再計算する。 */
    void deletePayment(Long invoiceId, Long paymentId);
    /** 請求書の入金履歴を入金日昇順で返す。 */
    List<InvoicePaymentResponse> listPayments(Long invoiceId);
    /** 顧客×経過区分の未回収エイジングレポート。 */
    AgingReportDto aging(LocalDate asOf);
    /** エイジング表の1セル（顧客×区分×基準日）を構成する請求書明細を返す（R3R-22）。 */
    List<com.ses.dto.invoice.InvoiceBalanceDto> agingDetail(Long customerId, String bucket, LocalDate asOf);
    /** 期限超過請求書への督促メールを送信する。 */
    MailDispatchResult sendReminder(Long invoiceId, Long templateId);
    List<MailDelivery> listReminders(Long invoiceId);
    List<com.ses.dto.mail.BulkReminderRowResult> sendReminders(List<Long> invoiceIds, Long templateId, LocalDate asOf);

    /**
     * 請求書が督促対象（未入金で期限超過）か判定する（A7-12）。
     */
    static boolean isOverdue(String status, LocalDate dueDate, LocalDate targetDate) {
        if (!("送付済".equals(status) || "一部入金".equals(status))) {
            return false;
        }
        return dueDate != null && dueDate.isBefore(targetDate);
    }

    /**
     * 一覧用：支払期限超過(未入金かつ期限日 < 今日)のみに絞り込む（A7-12）。
     */
    static void applyOverdueFilter(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Invoice> query) {
        query.in("status", "送付済", "一部入金")
             .isNotNull("due_date")
             .lt("due_date", java.time.LocalDate.now());
    }
}
