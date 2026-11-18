package com.ses.service.expense.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ExpenseAccountingJob;
import com.ses.entity.ExpenseRequest;
import com.ses.entity.IntegrationJob;
import com.ses.service.accounting.PurchaseExpensePaymentIntegrationService;
import com.ses.service.expense.ExpenseAccountingSender;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 承認済み経費をfreeeへ自動送信するsender（{@code expense.accounting.provider=freee}）。
 *
 * <p>freeeへの送信そのものは会計・支払連携の送信ジョブ（EXPENSE_DEAL_SYNC）に任せる。手動送信と
 * 同じマッピング照合・法人境界・管理番号長の検査と冪等性キーを通すため、送信経路は1本のまま。
 * このsenderは「送信ジョブを1件だけ作り、取引が作成されたら成功を返す」だけを行う:</p>
 * <ul>
 *   <li>送信ジョブが無い → ジョブを作成して {@link #PENDING}</li>
 *   <li>CANCELLED（管理者が取消した）→ {@link #CANCELLED}。終端で、作り直さない</li>
 *   <li>PENDING/RUNNING/RETRYABLE → {@link #PENDING}（会計workerが処理する。試行回数は消費しない）</li>
 *   <li>SUCCEEDED → 成功（correlationId = freee取引ID）。手動送信で先に作成された場合も同じ</li>
 *   <li>FAILED、または送信前の検査で拒否 → 失敗（理由は会計・支払連携の連携ジョブ一覧で確認する）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreeeExpenseAccountingSender implements ExpenseAccountingSender {

    public static final String PROVIDER_NAME = "freee";
    static final String TARGET_TYPE = "EXPENSE_REQUEST";
    static final String JOB_TYPE = "EXPENSE_DEAL_SYNC";
    /** 送信前の検査（未接続・マッピング未照合・法人未設定・締め済み等）で拒否された。 */
    static final String NOT_READY = "ACCOUNTING_NOT_READY";
    /** 会計側の送信ジョブがFAILEDになった。 */
    static final String JOB_FAILED = "ACCOUNTING_JOB_FAILED";

    private final IntegrationJobService jobService;
    private final ObjectProvider<PurchaseExpensePaymentIntegrationService> integrationService;

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public SendResult send(ExpenseRequest expense, ExpenseAccountingJob job) {
        if (expense == null || expense.getId() == null) {
            return new SendResult(false, null, "EXPENSE_NOT_FOUND");
        }
        IntegrationJob latest = jobService.getLatestJob(TARGET_TYPE, expense.getId(), JOB_TYPE);
        if (latest != null && "CANCELLED".equals(latest.getStatus())) {
            // 管理者の取消は終端。ここで作り直すと取消が次の実行で無効になる（再送信は画面から手動で行う）
            log.info("[経費会計連携] freee送信ジョブが取消済みのため自動送信を終了します: expenseId={} jobId={}",
                    expense.getId(), latest.getId());
            return new SendResult(false, null, CANCELLED);
        }
        if (latest == null) {
            try {
                latest = integrationService.getObject().triggerExpenseSync(expense.getId(), null);
            } catch (BusinessException e) {
                // 理由（message key）は連携ジョブ一覧・送信プレビューで確認できる。ここは分類codeだけ残す
                log.warn("[経費会計連携] freee送信ジョブを作成できません: expenseId={} reason={}",
                        expense.getId(), e.getMessage());
                return new SendResult(false, null, NOT_READY);
            }
        }
        if (latest == null) {
            return new SendResult(false, null, NOT_READY);
        }
        return switch (latest.getStatus() == null ? "" : latest.getStatus()) {
            case "SUCCEEDED" -> latest.getExternalId() == null || latest.getExternalId().isBlank()
                    ? new SendResult(false, null, JOB_FAILED)
                    : new SendResult(true, latest.getExternalId(), null);
            case "FAILED" -> new SendResult(false, null, JOB_FAILED);
            default -> new SendResult(false, null, PENDING);
        };
    }
}
