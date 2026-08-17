package com.ses.service.expense;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 経費申請サービス（T091 / engineer-self-service-portal-v2 B1）。
 * 状態機械: 下書き→申請中→承認済→会計連携済→支払済（design §6.3）。
 * 本人scopeは engineer-account link から解決し、リクエストのengineerIdを信用しない。
 * 承認は既存approval engine（requestType=expense.request、targetType=EXPENSE_REQUEST）へ委譲し、
 * 差戻し・競合は t_approval_request.status=returned/conflict をUIで導出する（leaveと同じ扱い）。
 */
public interface ExpenseRequestService {

    String CATEGORY_TRANSPORT = "交通費";
    String CATEGORY_REIMBURSEMENT = "立替経費";

    /** 本人が指定できる科目allowlist（design §4）。任意の科目codeを受け付けない。 */
    Set<String> CATEGORIES = Set.of(CATEGORY_TRANSPORT, CATEGORY_REIMBURSEMENT);

    String STATUS_DRAFT = "下書き";
    String STATUS_APPLIED = "申請中";
    String STATUS_APPROVED = "承認済";
    String STATUS_ACCOUNTING_SENT = "会計連携済";
    String STATUS_PAID = "支払済";

    /** 下書き作成/更新の入力。 */
    record ExpenseDraftCommand(LocalDate expenseDate, String category, BigDecimal amount,
                               Long customerId, Long projectId, String description) {
    }

    /** 一覧・詳細のレスポンス。approvalStatusはapproval engineのstatusを導出した値。 */
    record ExpenseRequestDto(
            Long id, String expenseNo, LocalDate expenseDate, String category, BigDecimal amount,
            Long customerId, Long projectId, String description, Long receiptDocumentId,
            Integer receiptVersionNo,
            String status, Long approvalRequestId, String approvalStatus,
            LocalDateTime paidAt, LocalDateTime createdAt, Long engineerId, String engineerName) {
    }

    /** 領収書ダウンロード結果。 */
    record ReceiptDownload(InputStream stream, String contentType, String originalName) {
    }

    // ----------------------------------------------------------------
    // 本人（/api/my/expenses。engineerIdはcontrollerがlink解決した本人ID）
    // ----------------------------------------------------------------

    Page<ExpenseRequestDto> pageForEngineer(Long engineerId, String status, long current, long size);

    ExpenseRequestDto createDraft(Long engineerId, ExpenseDraftCommand command);

    ExpenseRequestDto updateDraft(Long engineerId, Long id, ExpenseDraftCommand command);

    /** 下書きの削除（論理削除。領収書は文書台帳のため残す）。 */
    void deleteDraft(Long engineerId, Long id);

    /**
     * 下書き→申請中。expense_noをEX-{id}で採番し（null時のみ）、
     * approval engineへ申請を作成して approval_request_id を記録する。同一transaction。
     */
    ExpenseRequestDto submit(Long engineerId, Long id);

    /** 差戻し/競合（approval status=returned/conflict）からの再申請。 */
    ExpenseRequestDto resubmit(Long engineerId, Long id);

    /** 領収書を文書台帳（documentType=RECEIPT）へ登録しreceipt_document_idへ記録する。承認済以降は拒否。 */
    ExpenseRequestDto attachReceipt(Long engineerId, Long id, String originalName, String contentType,
                                    InputStream content);

    /** 本人の領収書ダウンロード。所有チェック後にscan=CLEANの最新版を開く。 */
    ReceiptDownload downloadReceipt(Long engineerId, Long id);

    // ----------------------------------------------------------------
    // 管理（/api/expense-requests。管理者=全件、マネージャー=組織scope配下）
    // ----------------------------------------------------------------

    Page<ExpenseRequestDto> pageManagement(String engineerName, String status, long current, long size);

    ExpenseRequestDto detailManagement(Long id);

    /** 会計連携済→支払済（状態CAS）。支払い通知EXPENSE_PAIDを本人へ発行する。 */
    ExpenseRequestDto markPaid(Long id);
}
